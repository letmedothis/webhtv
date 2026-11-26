#!/usr/bin/env python3
"""Generate release notes for push-triggered dev builds.

Reads commit subjects since the previous dev release tag, groups them by
Conventional Commits type (feat / fix / perf / refactor, everything else
folded into "other"), and writes a concise Chinese Markdown summary to
build/release-notes.md.

Environment variables:
  DEV_TAG      release tag, e.g. v5.6.0-dev.202611261540
  DEV_VERSION  APK versionName, e.g. 5.6.0-dev.4e30ffa
  SHORT_SHA    short commit hash
  EXTRA_NOTES  optional extra notes appended at the end (supports \n escapes)
"""

import os
import re
import subprocess

MAX_ENTRIES_PER_SECTION = 20

SUBJECT = re.compile(r"^(?P<type>[a-zA-Z]+)(?:\((?P<scope>[^)]+)\))?!?:\s+(?P<msg>.+)$")
SECTIONS = [
    ("feat", "✨ 新功能"),
    ("fix", "🐛 修复"),
    ("perf", "⚡ 性能"),
    ("refactor", "♻️ 重构"),
]
OTHER_LABEL = "📦 其他变更"


def git(*args):
    try:
        out = subprocess.run(["git", *args], capture_output=True, text=True, check=True)
        return out.stdout.strip()
    except subprocess.CalledProcessError:
        return ""


def collect_commits():
    """Return (commit subjects, range description) since the previous dev tag."""
    dev_tag = git("describe", "--tags", "--abbrev=0", "--match", "v*-dev.*")
    if dev_tag:
        scope = "自 `{}` 以来".format(dev_tag)
        subjects = git("log", "{}..HEAD".format(dev_tag), "--pretty=format:%s")
    else:
        any_tag = git("describe", "--tags", "--abbrev=0")
        if any_tag:
            scope = "自 `{}` 以来".format(any_tag)
            subjects = git("log", "{}..HEAD".format(any_tag), "--pretty=format:%s")
        else:
            scope = "最近提交"
            subjects = git("log", "-n", "40", "--pretty=format:%s")
    commits = [s.strip() for s in subjects.splitlines() if s.strip() and not s.strip().startswith("Merge ")]
    return commits, scope


def group_commits(commits):
    grouped = {label: [] for _, label in SECTIONS}
    grouped[OTHER_LABEL] = []
    labels = dict(SECTIONS)
    for subject in commits:
        m = SUBJECT.match(subject)
        if not m:
            grouped[OTHER_LABEL].append("- {}".format(subject))
            continue
        typ = m.group("type").lower()
        scope = m.group("scope")
        msg = m.group("msg")
        prefix = "**{}({})**".format(typ, scope) if scope else "**{}**".format(typ)
        entry = "- {}: {}".format(prefix, msg)
        grouped.get(labels.get(typ, OTHER_LABEL), grouped[OTHER_LABEL]).append(entry)
    return grouped


def main():
    tag = os.environ.get("DEV_TAG", "")
    version = os.environ.get("DEV_VERSION", "")
    short_sha = os.environ.get("SHORT_SHA", "")

    commits, scope = collect_commits()
    grouped = group_commits(commits)
    counts = {label: len(items) for label, items in grouped.items()}

    summary = [
        "✨ {} 项新功能".format(counts["✨ 新功能"]),
        "🐛 {} 项修复".format(counts["🐛 修复"]),
        "⚡ {} 项性能优化".format(counts["⚡ 性能"]),
        "📦 {} 项其他变更".format(counts[OTHER_LABEL]),
    ]
    visible = [s for s in summary if not s.startswith(("✨ 0", "🐛 0", "⚡ 0", "📦 0"))]

    lines = []
    lines.append("> 推送自动构建 · `{}` · 版本 `{}` · 提交 `{}`".format(tag, version, short_sha))
    lines.append("")
    lines.append("**{}共 {} 个提交：{}**".format(scope, len(commits), "、".join(visible) if visible else "无功能变更"))
    lines.append("")
    for label, items in grouped.items():
        if not items:
            continue
        lines.append("## {}（{}）".format(label, len(items)))
        lines.extend(items[:MAX_ENTRIES_PER_SECTION])
        if len(items) > MAX_ENTRIES_PER_SECTION:
            lines.append("- ……另有 {} 条未展开".format(len(items) - MAX_ENTRIES_PER_SECTION))
        lines.append("")

    extra = os.environ.get("EXTRA_NOTES", "").strip()
    if extra:
        extra = extra.replace("\\r\\n", "\n").replace("\\n", "\n")
        lines.append("## 附加说明")
        lines.append(extra)
        lines.append("")

    lines.append("---")
    lines.append("*开发预发布版（prerelease），仅供尝鲜测试；正式版请使用 Android Release 工作流手动发布。*")
    lines.append("")

    os.makedirs("build", exist_ok=True)
    with open("build/release-notes.md", "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines))
    print("Wrote build/release-notes.md ({} commits)".format(len(commits)))


if __name__ == "__main__":
    main()
