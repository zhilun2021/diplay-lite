#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
工程文档一致性审计（DiPlay 私有分支专用，只读扫描 + 可选写回，不联网）

做什么：
  1) 扫 工程文档/ 下全部 .md，读 YAML front-matter（没有则按文件名猜类型）
  2) 按三类报异常（每条带 E/W 级别）：
     A 索引登记对账
       E4 00-索引.md 登记了但文件不存在
       W2 00-索引.md 漏登（根目录文档必须按名登记；归档目录整体豁免；
          支持 `方案-*.md` / `日志分析-YYYYMMDD-*.md` 这类通配登记）
     B 内部链接与引用
       E2 死链（现行文档 = 索引/常青规范/审核清单）
       E3 状态=已废弃 但仍被其它文档引用
       W3 用了 #L数字 行号互链（违反 00-索引 §五.3）
       W4 路径漂移 / 冻结文档死链（仅提示，不阻断）
     C 文档头与元数据
       E1 标题重名（多个文件 H1 相同）
       E5 front-matter 类型/状态非法、非快照缺状态
       W1 缺 front-matter / 缺字段（仅对索引、常青规范、审核清单强制）
  3) --write-index 生成全量登记表 工程文档/00-索引-全量登记.md

退出码：
  0 = 干净
  1 = 只有提示（W 类）
  2 = 有错（E 类）
  3 = 环境异常（找不到 工程文档/）

用法：
  python scripts/doc_audit.py                    # 只读审计，打印报告
  python scripts/doc_audit.py --json             # 机器可读（CI 汇总用）
  python scripts/doc_audit.py --write-index      # 审计 + 生成全量登记表
  python scripts/doc_audit.py --paths a.md b.md  # 只审计指定文件（供 pre-commit 用）

挂载建议：
  * pre-commit：只对本次暂存的 `工程文档/**/*.md` 跑 `--paths`，要求 exit=0；
    改动当场拦截，成本只与本次改动量有关。
  * 发版前：全量跑一次（不加 `--paths`），要求 exit≤1（即无 E），逐条确认 W；
    再 `--write-index` 生成全量登记，随发布提交一起入库。全量审计才能覆盖
    「标题重名 / 索引漏登 / 跨文档引用」这类需要全语料的检查。
"""
import argparse
import datetime
import json
import os
import re
import sys
from urllib.parse import unquote

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOC_ROOT = os.path.join(ROOT, "工程文档")
INDEX_FILE = os.path.join(DOC_ROOT, "00-索引.md")
GEN_INDEX = os.path.join(DOC_ROOT, "00-索引-全量登记.md")

TYPE_ORDER = ["索引", "常青规范", "方案评估", "审核清单", "快照"]
VALID_TYPES = set(TYPE_ORDER)
VALID_STATUS = {"现行", "已废弃", "草稿"}

# 归档目录：00-索引 只按目录登记、不逐篇登记，命中这些前缀不判漏登
ARCHIVE_DIRS = ("06-排障-档案/", "07-沿革-原始档案/", "08-路线图-档案/", "09-逆向-车机与取证/")

# 死链按类型分级：现行文档（索引/常青/审核清单）算错；日期冻结类（方案评估/快照）只提示
LINK_BLOCKING_TYPES = {"索引", "常青规范", "审核清单"}

# 只有长期维护的三类强制要求文档头；快照/方案评估只登记
NEEDS_FM_TYPES = {"索引", "常青规范", "审核清单"}

# 索引里出现这些字符的是占位模板或说明文字，不做存在性校验
PLACEHOLDER = ("*", "<", ">", "…", "YYYY", "NN-", "{", "}", "（", "）")

FM_KEYS = ["类型", "状态", "生效版本", "适用版本", "快照日期", "最后核对", "取代", "被取代"]

STATUS_WORDS = ("已废弃", "作废", "不再维护", "已归档", "仅存档", "历史档案", "已被取代", "冻结")

# 报告按三类聚合（只影响展示，不影响退出码）
CATEGORY = {
    "E4": "A 索引登记对账", "W2": "A 索引登记对账",
    "E2": "B 内部链接与引用", "E3": "B 内部链接与引用",
    "W3": "B 内部链接与引用", "W4": "B 内部链接与引用",
    "E1": "C 文档头与元数据", "E5": "C 文档头与元数据", "W1": "C 文档头与元数据",
}
CATEGORIES = ("A 索引登记对账", "B 内部链接与引用", "C 文档头与元数据")


# ---------------------------------------------------------------- 文件与解析

def iter_docs(paths=None):
    if paths:
        for p in paths:
            ap = p if os.path.isabs(p) else os.path.join(ROOT, p)
            if ap.lower().endswith(".md") and os.path.exists(ap):
                yield ap
        return
    for dirpath, dirnames, filenames in os.walk(DOC_ROOT):
        dirnames[:] = sorted(d for d in dirnames if d not in (".git", "node_modules"))
        for fn in sorted(filenames):
            if fn.lower().endswith(".md"):
                yield os.path.join(dirpath, fn)


def rel_of(path):
    return os.path.relpath(path, ROOT).replace("\\", "/")


def read_raw(path):
    with open(path, "rb") as f:
        return f.read()


def eol_of(raw):
    return b"\r\n" if raw.count(b"\r\n") >= (raw.count(b"\n") - raw.count(b"\r\n")) else b"\n"


def decode(raw):
    return raw.decode("utf-8-sig", errors="replace")


def parse_front_matter(text):
    """返回 (dict, 正文起始行号, 是否有 front-matter)。只认文件第一行的 ---。"""
    lines = text.split("\n")
    if not lines or lines[0].strip() != "---":
        return {}, 0, False
    fm = {}
    for i in range(1, min(len(lines), 200)):
        s = lines[i].strip()
        if s in ("---", "..."):
            return fm, i + 1, True
        if not s or s.startswith("#"):
            continue
        m = re.match(r"^([^:：]+)[:：]\s*(.*)$", s)
        if m:
            k = m.group(1).strip()
            v = m.group(2).strip()
            if (v.startswith('"') and v.endswith('"')) or (v.startswith("'") and v.endswith("'")):
                v = v[1:-1]
            fm[k] = v
        elif s.startswith("- ") and fm:
            last = list(fm.keys())[-1]
            fm[last] = (fm[last] + "," + s[2:].strip()).strip(",")
    return {}, 0, False


def h1_of(text):
    for line in text.split("\n"):
        m = re.match(r"^#\s+(.+?)\s*$", line)
        if m:
            return m.group(1).strip()
    return ""


def guess_type(rel):
    """没有 front-matter 时，按路径/文件名猜类型（仅用于报告分组）。"""
    base = os.path.basename(rel)
    for d in ARCHIVE_DIRS:
        if rel.startswith("工程文档/" + d):
            return "快照"
    if base in ("00-索引.md", "README.md") or base.endswith("-索引.md") or base == "00-索引-全量登记.md":
        return "索引"
    if re.search(r"20\d{6}", base):
        if re.search(r"日志分析|^20\d{6}", base):
            return "快照"
        if base.startswith("审核清单-"):
            return "审核清单"   # 出包前清单按版本维护，需要文档头
        return "方案评估"       # 审查/评审/测试/分析/方案 带日期即冻结，只登记
    if base.startswith(("审核清单-", "审查-", "评审-", "测试-")):
        return "审核清单"
    if base.startswith(("方案-", "分析-", "竞品分析-", "竞品逆向-", "待办-全量盘点")):
        return "方案评估"
    if base.startswith(("CM390-", "T3-HY3", "T3-native")):
        return "方案评估"
    return "常青规范"


def strip_code(text):
    """去掉 ``` 代码块内容，避免把示例里的链接当死链。"""
    out, in_code = [], False
    for line in text.split("\n"):
        if line.strip().startswith("```"):
            in_code = not in_code
            out.append("")
            continue
        out.append("" if in_code else line)
    return "\n".join(out)


LINK_RE = re.compile(r"\[[^\]]*\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")
REF_RE = re.compile(r"^\[[^\]]+\]:\s*(\S+)\s*$", re.M)
BACKTICK_MD_RE = re.compile(r"`([^`\n]+\.md)`")
BACKTICK_ANY_RE = re.compile(r"`([^`\n]+)`")   # 索引里还登记 `竞品逆向-*` 这类无 .md 的通配模式


def collect_links(text, self_dir):
    """返回 [(原始目标, 解析后的绝对路径或 None)]，跳过 http/anchor。"""
    out = []
    body = strip_code(text)
    targets = [m.group(1) for m in LINK_RE.finditer(body)]
    targets += [m.group(1) for m in REF_RE.finditer(body)]
    for t in targets:
        t = t.strip("<>")
        if t.startswith(("http://", "https://", "mailto:", "tel:", "#")):
            continue
        t = unquote(t.split("#", 1)[0])
        if not t or not t.lower().endswith(".md"):
            continue
        out.append((t, os.path.normpath(os.path.join(self_dir, t)))
                         if not os.path.isabs(t)
                         else (t, os.path.normpath(t)))
    return out


# ---------------------------------------------------------------- 审计

def audit(paths=None):
    files = list(iter_docs(paths))
    docs, warnings, errors = [], [], []

    all_rel = set()
    all_base = {}
    for p in iter_docs():
        r = rel_of(p)
        all_rel.add(r)
        all_base.setdefault(os.path.basename(r), []).append(r)

    for p in files:
        raw = read_raw(p)
        text = decode(raw).replace("\r\n", "\n").replace("\r", "\n")
        rel = rel_of(p)
        if not rel.startswith("工程文档/"):
            continue
        fm, body_start, has_fm = parse_front_matter(text)
        body = "\n".join(text.split("\n")[body_start:]) if has_fm else text
        h1 = h1_of(body)
        dtype = fm.get("类型") or guess_type(rel)
        status = fm.get("状态", "")
        doc = {
            "rel": rel,
            "file": os.path.relpath(p, DOC_ROOT).replace("\\", "/"),
            "dir": os.path.dirname(os.path.relpath(p, DOC_ROOT)).replace("\\", "/"),
            "h1": h1,
            "type": dtype,
            "type_src": "front-matter" if fm.get("类型") else "filename",
            "status": status,
            "version": fm.get("生效版本") or fm.get("适用版本") or "",
            "last_check": fm.get("最后核对") or fm.get("快照日期") or "",
            "supersedes": fm.get("取代", ""),
            "superseded_by": fm.get("被取代", ""),
            "has_fm": has_fm,
            "fm": fm,
            "status_judgeable": "",
            "body": body,
        }

        # 状态可否从正文判断（没有 front-matter 时用关键词兜底）
        if dtype == "快照":
            doc["status_judgeable"] = "不适用（快照只登记）"
        elif status:
            doc["status_judgeable"] = "是（front-matter）"
        else:
            hit = next((w for w in STATUS_WORDS if w in text[:3000]), "")
            doc["status_judgeable"] = ("是（正文关键词「%s」）" % hit) if hit else "否"

        # front-matter 合法性
        if not has_fm:
            if dtype in NEEDS_FM_TYPES:
                warnings.append(("W1", rel, "缺 front-matter"))
        else:
            if dtype not in VALID_TYPES:
                errors.append(("E5", rel, "front-matter 类型非法：%s" % dtype))
            if dtype == "快照":
                if not fm.get("快照日期"):
                    warnings.append(("W1", rel, "快照缺「快照日期」"))
            else:
                if not status:
                    errors.append(("E5", rel, "非快照文档缺「状态」"))
                elif status not in VALID_STATUS:
                    errors.append(("E5", rel, "front-matter 状态非法：%s" % status))
                if status == "已废弃" and fm.get("被取代", "无") in ("", "无"):
                    warnings.append(("W1", rel, "状态=已废弃 但「被取代」未填"))
                for k in ("最后核对", "取代", "被取代"):
                    if not fm.get(k):
                        warnings.append(("W1", rel, "缺字段「%s」" % k))
                if not (fm.get("生效版本") or fm.get("适用版本")):
                    warnings.append(("W1", rel, "缺「生效版本」/「适用版本」"))

        # 内部链接：同名文件别处存在 → 路径漂移（提示）；否则才判死链
        for orig, ap in collect_links(text, os.path.dirname(p)):
            if os.path.exists(ap):
                continue
            same = all_base.get(os.path.basename(ap), [])
            if same:
                warnings.append(("W4", rel, "路径漂移：%s（同名文件在 %s）" % (orig, same[0])))
            elif dtype in LINK_BLOCKING_TYPES:
                errors.append(("E2", rel, "死链：%s" % orig))
            else:
                warnings.append(("W4", rel, "死链（冻结/非现行文档，仅提示）：%s" % orig))
        if re.search(r"\]\([^)]*#L\d+", text):
            warnings.append(("W3", rel, "使用 #L数字 行号互链"))

        docs.append(doc)

    # E1 标题重名
    by_h1 = {}
    for d in docs:
        if d["h1"]:
            by_h1.setdefault(d["h1"], []).append(d["rel"])
    for h1, group in sorted(by_h1.items()):
        if len(group) > 1:
            errors.append(("E1", group[0], "标题重名「%s」：%s" % (h1, " | ".join(group))))

    # E3 已废弃仍被引用
    deprecated = [d for d in docs if d["status"] == "已废弃"]
    for dep in deprecated:
        base = os.path.basename(dep["rel"])
        stem = base[:-3]
        for d in docs:
            if d["rel"] == dep["rel"]:
                continue
            if base in d["body"] or (stem in d["body"] and stem not in ("00-索引",)):
                errors.append(("E3", d["rel"], "引用了已废弃文档 %s" % base))

    # E4 索引登记对账（只在全文审计时做）
    if not paths and os.path.exists(INDEX_FILE):
        idx_text = decode(read_raw(INDEX_FILE)).replace("\r\n", "\n")
        tokens = set(m.group(1).strip() for m in BACKTICK_ANY_RE.finditer(idx_text))
        tokens |= set(m.group(1) for m in LINK_RE.finditer(idx_text) if m.group(1).lower().endswith(".md"))
        tokens |= set(m.group(1) for m in REF_RE.finditer(idx_text))
        registered_rel, registered_base, registered_patterns = set(), set(), []

        def norm_token(t):
            t = unquote(t.split("#", 1)[0]).strip().strip("<>")
            while t.startswith("./"):
                t = t[2:]
            return t

        def to_regex(t):
            """登记模式转正则（按 basename 匹配）：`*`→[^/]*，YYYYMMDD→8 位日期。"""
            esc = re.escape(t)
            esc = esc.replace(r"\*", "[^/]*")
            esc = esc.replace("YYYYMMDD", r"\d{8}")
            esc = esc.replace("YYYY", r"\d{4}")
            return re.compile("^" + esc + "$")

        for t in tokens:
            t = norm_token(t)
            is_doc = t.lower().endswith(".md")
            if not is_doc and not t.endswith("*"):
                continue  # 非文档路径（目录、命令、字段名）不参与对账
            if any(ch in t for ch in ("<", ">", "…", "{", "}", "（", "）")) or "NN-" in t:
                continue  # 说明性占位（如 `<日期>.md`、`NN-层名-主题.md`）
            if "*" in t or "YYYY" in t:
                pat = (t if is_doc else t + ".md").rsplit("/", 1)[-1]
                registered_patterns.append(to_regex(pat))
                continue
            if not is_doc:
                continue
            if "/" in t:
                # 先按 工程文档/ 解析，再按仓库根解析（索引里有 `../当前状态.md` 这类跨目录登记）
                cand = os.path.normpath(os.path.join(DOC_ROOT, t))
                if not os.path.exists(cand):
                    cand = os.path.normpath(os.path.join(ROOT, t))
                if os.path.exists(cand):
                    registered_rel.add(rel_of(cand))
                    registered_base.add(os.path.basename(cand))
                else:
                    errors.append(("E4", "工程文档/00-索引.md", "登记了但文件不存在：%s" % t))
            else:
                if t in all_base:
                    registered_base.add(t)
                    for r in all_base[t]:
                        registered_rel.add(r)
                elif os.path.exists(os.path.join(ROOT, t)):
                    registered_base.add(t)   # 仓库根级文件（如 `当前状态.md`）
                elif os.path.exists(os.path.join(DOC_ROOT, t)):
                    registered_base.add(t)
                else:
                    errors.append(("E4", "工程文档/00-索引.md", "登记了但文件不存在：%s" % t))

        unreg = []
        for d in docs:
            base = os.path.basename(d["rel"])
            if d["rel"] in registered_rel or base in registered_base:
                continue
            if any(rx.match(base) for rx in registered_patterns):
                continue
            inner = d["file"]
            if inner.startswith(ARCHIVE_DIRS):
                continue  # 归档目录整体登记，不逐篇要求
            if base == "00-索引-全量登记.md":
                continue
            if inner == "00-索引.md":
                continue
            unreg.append(d)
        for d in unreg:
            warnings.append(("W2", d["rel"], "00-索引.md 漏登"))

    return docs, warnings, errors


# ---------------------------------------------------------------- 输出

def report(docs, warnings, errors):
    print("=" * 78)
    print("工程文档审计 · 共 %d 篇" % len(docs))
    print("=" * 78)

    for t in TYPE_ORDER:
        group = [d for d in docs if d["type"] == t]
        print("\n[%s] %d 篇" % (t, len(group)))
        for d in sorted(group, key=lambda x: x["file"]):
            flag = "FM " if d["has_fm"] else "   "
            print("  %s %-58s 状态=%-6s 版本=%-8s 核对=%s" % (
                flag, d["file"], d["status"] or "-", d["version"] or "-", d["last_check"] or "-"))

    print("\n" + "-" * 78)
    print("状态可判断性")
    print("-" * 78)
    unknown = [d for d in docs if d["status_judgeable"] == "否"]
    print("  可从正文/front-matter 判断: %d 篇" % (len(docs) - len(unknown)))
    print("  无法判断: %d 篇" % len(unknown))
    for d in unknown[:40]:
        print("    - %s" % d["file"])

    print("\n" + "=" * 78)
    print("异常 · 错误 %d 条 / 提示 %d 条（退出码 %d）" % (
        len(errors), len(warnings), 2 if errors else (1 if warnings else 0)))
    print("=" * 78)
    for cat in CATEGORIES:
        e = [x for x in errors if CATEGORY.get(x[0]) == cat]
        w = [x for x in warnings if CATEGORY.get(x[0]) == cat]
        print("\n[%s]  错误 %d / 提示 %d" % (cat, len(e), len(w)))
        if not e and not w:
            print("  无")
        for code, rel, msg in sorted(e):
            print("  E  %-46s %s" % (rel, msg))
        for code, rel, msg in sorted(w):
            print("  W  %-46s %s" % (rel, msg))
    print()


def write_index(docs):
    eol = eol_of(read_raw(INDEX_FILE)) if os.path.exists(INDEX_FILE) else b"\n"
    today = datetime.date.today().isoformat()
    lines = [
        "---",
        "类型: 索引",
        "状态: 现行",
        "生效版本: 持续有效",
        "最后核对: %s" % today,
        "取代: 无",
        "被取代: 无",
        "---",
        "",
        "# 00 · 索引 · 全量登记（自动生成）",
        "",
        "> 由 `scripts/doc_audit.py --write-index` 生成，**请勿手改**；手工入口见 `00-索引.md`。",
        "> 类型/状态/版本/取代关系取自各文档 YAML front-matter；快照类只登记、不判现行。",
        "",
        "| 类型 | 状态 | 版本 | 最后核对 | 文件 | 标题 |",
        "|---|---|---|---|---|---|",
    ]
    order = {t: i for i, t in enumerate(TYPE_ORDER)}
    for d in sorted(docs, key=lambda x: (order.get(x["type"], 99), x["file"])):
        if d["file"] == "00-索引-全量登记.md":
            continue
        lines.append("| %s | %s | %s | %s | `%s` | %s |" % (
            d["type"], d["status"] or "—", d["version"] or "—",
            d["last_check"] or "—", d["file"], (d["h1"] or "—").replace("|", "\\|")))
    data = (eol.join(x.encode("utf-8") for x in lines) + eol)
    with open(GEN_INDEX, "wb") as f:
        f.write(data)
    print("已生成 %s（%d 行）" % (rel_of(GEN_INDEX), len(lines)))


def main():
    ap = argparse.ArgumentParser(description="工程文档一致性审计")
    ap.add_argument("--json", action="store_true", help="输出 JSON")
    ap.add_argument("--write-index", action="store_true", help="生成 00-索引-全量登记.md")
    ap.add_argument("--paths", nargs="*", help="只审计指定文件（供 pre-commit 用）")
    args = ap.parse_args()

    if not os.path.isdir(DOC_ROOT):
        print("找不到 %s" % DOC_ROOT, file=sys.stderr)
        return 3

    docs, warnings, errors = audit(args.paths or None)

    if args.json:
        print(json.dumps({
            "total": len(docs),
            "docs": [{k: v for k, v in d.items() if k != "body"} for d in docs],
            "warnings": warnings,
            "errors": errors,
        }, ensure_ascii=False, indent=1))
    else:
        report(docs, warnings, errors)

    if args.write_index:
        write_index(docs)

    if errors:
        return 2
    if warnings:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
