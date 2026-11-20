#!/usr/bin/env python3
"""状态机 ratchet 检查（vc301 P0 起固化）。

用途：静态验收时证明「收口类改动只降不升」——超时常量、看门狗、线程、拆栈/失败
调用点、热点复用参数不得增加；新增的 decide() 出口允许从 0 增加。

例外只有一种走法：在本文件的 AUTHORIZED_INCREASE 里**按 (base, 计数项) 逐次登记**
允许的增量 + 理由 + 锚点正则。整类放行（把人改成 True）一律不接受——那样下一次
偷偷加线程就再也拦不住了。

用法：
    python scripts/check_state_ratchet.py            # a00f4c7 vs 工作区
    python scripts/check_state_ratchet.py --base a00f4c7
    python scripts/check_state_ratchet.py --file <path>

退出码：0 = 全部持平或下降（含已登记的授权上升）；1 = 有计数上升。
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

DEFAULT_FILE = (
    "diplay-lite/shared/src/main/java/com/shilapi/xcertplay/orchestration/"
    "CarPlayController.kt"
)

# (显示名, 正则, 是否允许上升)
PATTERNS = [
    ("TIMEOUT_MILLIS", r"TIMEOUT_MILLIS", False),
    ("arm*Watchdog", r"arm\w*Watchdog", False),
    ("Thread(", r"Thread\(", False),
    ("keepHotspotForReuse", r"keepHotspotForReuse", False),
    ("closeWirelessStack(", r"closeWirelessStack\(", False),
    ("fail(", r"(?<![\w.])fail\(", False),
    # 显式失败分类点数：禁止悄悄退化成位置参数/Unknown。
    ("显式 failureClass", r"CarPlayFailureClass\.CredTimeout", False),
    ("decide(", r"decide\(", True),  # 仅 P0 豁免，P1 起钳制
]

# 授权上升（逐版登记，整类不放行）。
# key = (base 引用, 计数项)；value = (允许增量, 理由, 锚点正则——必须在当前文本里命中，
# 否则不放行，防止这条口子被挪去盛放别的改动)。
AUTHORIZED_INCREASE: dict[tuple[str, str], tuple[int, str, str | None]] = {
    ("vc309", "Thread("): (
        1,
        "vc310：唯一新增执行体 = 只读热点接口采样线程（HOT-309-01 阶段1 / P0-302-02 P0，"
        "5s 一拍、世代绑定自退、只打日志不参与判断）",
        r'"xcertplay-hotspot-liveness"',
    ),
}


def count(text: str, pattern: str) -> int:
    return len(re.findall(pattern, text))


def read_base(rev: str, path: str) -> str:
    try:
        return subprocess.check_output(
            ["git", "show", f"{rev}:{path}"],
            text=True,
            encoding="utf-8",
            errors="replace",
        )
    except subprocess.CalledProcessError as error:
        print(f"[ratchet] 无法读取基线 {rev}:{path}: {error}", file=sys.stderr)
        raise SystemExit(2)


def main() -> int:
    parser = argparse.ArgumentParser(description="CarPlayController 状态机 ratchet 检查")
    parser.add_argument(
        "--base",
        default="a00f4c7",
        help="基线 git 引用（默认固定为本期 vc300 源码基线 a00f4c7）",
    )
    parser.add_argument("--file", default=DEFAULT_FILE, help="目标源码路径")
    args = parser.parse_args()

    path = Path(args.file)
    if not path.is_file():
        print(f"[ratchet] 文件不存在: {path}", file=sys.stderr)
        return 2

    base_text = read_base(args.base, args.file)
    now_text = path.read_text(encoding="utf-8", errors="replace")

    print(f"[ratchet] base={args.base}  file={args.file}")
    print(f"{'计数项':<24}{'基线':>6}{'当前':>6}{'结果':>8}")
    violations: list[str] = []
    for name, pattern, allow_increase in PATTERNS:
        before = count(base_text, pattern)
        now = count(now_text, pattern)
        if now > before and not allow_increase:
            extra = now - before
            limit, reason, anchor = AUTHORIZED_INCREASE.get(
                (args.base, name), (0, "", None)
            )
            if extra <= limit and (anchor is None or re.search(anchor, now_text)):
                print(f"[ratchet] 授权上升：{name} +{extra} —— {reason}")
                verdict = "AUTH"
            else:
                verdict = "FAIL"
                violations.append(name)
        elif now > before:
            verdict = "NEW"
        elif now < before:
            verdict = "DOWN"
        else:
            verdict = "OK"
        print(f"{name:<24}{before:>6}{now:>6}{verdict:>8}")

    if violations:
        print(f"[ratchet] 失败：以下计数上升 {violations}", file=sys.stderr)
        return 1
    print("[ratchet] 通过：收口类计数只降不升或持平")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
