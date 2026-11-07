#!/usr/bin/env python3
"""Public-tree credential guard.

Rejects credential containers, private keys, ability-signed binaries and local
evidence archives in the tree that is actually published.

Why this exists: the MFi accessory identity (``identity.pk8`` / ``certificate.p7b``)
used to be tracked in Git and reached the public branch history. Committing a key
does not make it usable later and cannot be undone by deleting it afterwards --
any clone still carries the object. This guard is the barrier that should have
existed in the first place.

Semantics (2026-11-06 revised):
  * Counts and scans the **public tree of a ref** (``git archive <ref>``), NOT the
    private worktree. ``git ls-files`` on the private branch answers a different
    question and must never be quoted as "the public payload".
  * Both path patterns and file content are checked, because a key can be a raw
    PEM block dropped anywhere in the tree.
  * "Count" and "rejected" are reported separately: the count is the size of the
    public payload, rejection is the verdict on it.

Ref selection:
  * ``--ref`` wins when given.
  * Otherwise the first existing ref of ``public/main`` then ``github-main`` is
    used. Both point at the public branch locally; ``public/main`` is the remote
    tracking ref of the Gitee public repo, so it is preferred as the ground truth.

Usage:  python3 scripts/check_public_tree.py [--ref REF]
Exits 0 when nothing is rejected, 1 when something is.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import tarfile

# Path patterns that must never enter the public tree.
REJECTED_SUFFIXES = (
    # accessory / code-signing identity containers
    ".pk8", ".p7b", ".p12", ".pfx", ".key", ".keystore", ".jks",
    ".p8", ".der", ".cer", ".pem",
    # installable distribution artifacts (they bundle the identity)
    ".apk", ".aab", ".idsig", ".apks", ".xapk",
)

# Whole directories that are local-only by convention.
REJECTED_PREFIXES = (
    "local-archive/",   # forensics, decompilation output, device logs
    "tmp-analysis/",    # logcat captures, screen recordings, log fires
    "docs-local/",      # private reverse-engineering and planning notes
    "logs/",
    "gitee-logs/",
    "t3-root-debug/",
    "hardware-stage/",
    "backup/",
    ".private/",
    "auth/",            # local staging dir for runtime identity assets
)

REJECTED_BASENAMES = {
    "release-signing.properties",
    "local.properties",   # carries GITEE_LOG_TOKEN
}

# Content patterns searched inside tracked text files.
CONTENT_PATTERNS = (
    # Require a base64 body after the header so that obfuscated string constants
    # (deliberately scrambled markers, which carry no key material) do not trip the
    # check, while a real inlined PEM does.
    ("private key block",
     re.compile(rb"-----BEGIN [A-Z ]*PRIVATE KEY-----\s*\r?\n[A-Za-z0-9+/=]{40,}")),
    # Credentials embedded directly into remote URLs, e.g. the Gitee/GitHub remotes
    # that carry a personal access token. Never match a literal secret here -- this
    # script itself ships in the public tree, so it must never contain one.
    #
    # The credential segment deliberately excludes '<' and '>': documentation
    # routinely shows the push recipe as `https://oauth2:<TOKEN>@gitee.com/...`,
    # and flagging that placeholder would fail the build for a file that is in fact
    # telling the reader never to persist a token. A real token has no angle
    # brackets, so it is still caught.
    ("credentials in remote URL",
     re.compile(rb"https://[^\s/:@]+:[^\s/:@<>]+@(?:gitee|github)\.com")),
    ("GitHub PAT", re.compile(rb"\bghp_[A-Za-z0-9]{20,}\b")),
    ("hardcoded secret assignment",
     re.compile(rb"(?i)\b(token|password|secret|api_?key)\s*[=:]\s*[\"'][^\"'\s]{8,}[\"']")),
)

MAX_CONTENT_SCAN_BYTES = 2 * 1024 * 1024
TEXT_SAMPLE_BYTES = 8192

PREFERRED_REFS = ("public/main", "github-main")


def rev_exists(ref: str) -> bool:
    result = subprocess.run(
        ["git", "rev-parse", "--verify", "--quiet", ref + "^{commit}"],
        capture_output=True,
    )
    return result.returncode == 0


def resolve_ref(explicit: str | None) -> str:
    if explicit:
        if rev_exists(explicit):
            return explicit
        raise SystemExit(
            "check_public_tree: ref '%s' does not resolve to a commit. "
            "Pass --ref with a public ref (e.g. --ref public/main)." % explicit
        )
    for candidate in PREFERRED_REFS:
        if rev_exists(candidate):
            return candidate
    raise SystemExit(
        "check_public_tree: no public ref found (tried %s). "
        "Run: git fetch public, or pass --ref explicitly." % ", ".join(PREFERRED_REFS)
    )


def short_sha(ref: str) -> str:
    return subprocess.run(
        ["git", "rev-parse", "--short", ref + "^{commit}"],
        capture_output=True, text=True, check=True,
    ).stdout.strip()


def scan_tree(ref: str):
    """Stream the public tree once: return (paths, rejected_path, rejected_content)."""
    proc = subprocess.Popen(["git", "archive", "--format=tar", ref],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    paths: list[str] = []
    by_path: list[tuple[str, str]] = []
    by_content: list[tuple[str, str]] = []
    rejected = set()

    with tarfile.open(fileobj=proc.stdout, mode="r|") as archive:
        for member in archive:
            if not member.isfile():
                continue
            path = member.name
            paths.append(path)
            lowered = path.lower()

            if lowered.endswith(REJECTED_SUFFIXES):
                by_path.append((path, "credential container or build artifact"))
                rejected.add(path)
                continue
            if lowered.startswith(REJECTED_PREFIXES):
                by_path.append((path, "local-only directory"))
                rejected.add(path)
                continue
            if lowered.split("/")[-1] in REJECTED_BASENAMES:
                by_path.append((path, "local secrets file"))
                rejected.add(path)
                continue

            handle = archive.extractfile(member)
            if handle is None:
                continue
            blob = handle.read(MAX_CONTENT_SCAN_BYTES)
            if b"\0" in blob[:TEXT_SAMPLE_BYTES]:
                continue  # binary attachments that passed the suffix check are fine
            for label, pattern in CONTENT_PATTERNS:
                if pattern.search(blob):
                    by_content.append((path, label))
                    rejected.add(path)
                    break

    err = proc.stderr.read().decode("utf-8", "replace").strip()
    if proc.wait() != 0:
        raise SystemExit("check_public_tree: git archive failed: %s" % err)

    return paths, by_path, by_content, rejected


def main() -> int:
    parser = argparse.ArgumentParser(description="Public-tree credential guard")
    parser.add_argument(
        "--ref",
        default=None,
        help="Public ref to inspect. Default: first of %s that exists."
             % ", ".join(PREFERRED_REFS),
    )
    args = parser.parse_args()

    ref = resolve_ref(args.ref)
    paths, by_path, by_content, rejected = scan_tree(ref)

    print("check_public_tree: public tree @ %s (%s): %d tracked files"
          % (ref, short_sha(ref), len(paths)))

    if not rejected:
        print("check_public_tree: OK - 0 of %d rejected by path rules, "
              "0 of %d rejected by content rules"
              % (len(paths), len(paths)))
        return 0

    print("check_public_tree: FAILED - %d of %d public-tree files rejected"
          % (len(rejected), len(paths)), file=sys.stderr)
    print("", file=sys.stderr)
    if by_path:
        print("-- rejected by path (%d) --" % len(by_path), file=sys.stderr)
        for path, reason in by_path:
            print("  %s  (%s)" % (path, reason), file=sys.stderr)
        print("", file=sys.stderr)
    if by_content:
        print("-- rejected by content (%d) --" % len(by_content), file=sys.stderr)
        for path, label in by_content:
            print("  %s  (%s)" % (path, label), file=sys.stderr)
        print("", file=sys.stderr)
    print("Remove them from the public tree and add the pattern to the sync"
          " whitelist. Note: deleting a file does not remove it from Git history"
          " -- rewriting the history is required in that case.", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
