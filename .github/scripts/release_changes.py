#!/usr/bin/env python3
"""Print a Markdown "What changed" section for a fork release.

Lists the upstream commits between the previous AerioTV-Brxdy22Fork release and
this one. The previous release is the newest fork release published before this
one (other than this tag). If there is none, it falls back to the upstream
release before this one (only when this build is an upstream tag).

Usage: release_changes.py --tag fireos6-main-abc1234 --new-ref <upstream sha or tag>
       [--before 2026-11-04T14:23:05Z]   (backfilling an existing release)
Needs GH_TOKEN (or GITHUB_TOKEN) in the environment; GITHUB_REPOSITORY for the fork.
"""
import argparse
import json
import os
import re
import sys
import urllib.request

UPSTREAM = os.getenv("UPSTREAM_REPO", "jonzey231/AerioTV-Android")
MAX_LINES = 40


def api(path):
    token = os.getenv("GH_TOKEN") or os.getenv("GITHUB_TOKEN") or ""
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "aeriotv-release-notes"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(f"https://api.github.com/{path}", headers=headers)
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def upstream_ref_for_tag(tag):
    """fireos6-main-<sha7> -> <sha7>; fireos6-v0.5.13 -> v0.5.13."""
    m = re.fullmatch(r"fireos6-main-([0-9a-f]{7,40})", tag)
    if m:
        return m.group(1)
    m = re.fullmatch(r"fireos6-(v[0-9][\w.\-]*)", tag)
    return m.group(1) if m else None


def previous_fork_release(releases, tag, before=None):
    candidates = [r for r in releases
                  if not r.get("draft") and r.get("published_at") and r["tag_name"] != tag
                  and upstream_ref_for_tag(r["tag_name"])
                  and (before is None or r["published_at"] < before)]
    candidates.sort(key=lambda r: r["published_at"], reverse=True)
    return candidates[0] if candidates else None


def previous_upstream_release(upstream_releases, new_ref):
    tags = [r["tag_name"] for r in sorted(
        (r for r in upstream_releases if not r.get("draft") and r.get("published_at")),
        key=lambda r: r["published_at"], reverse=True)]
    if new_ref in tags and tags.index(new_ref) + 1 < len(tags):
        return tags[tags.index(new_ref) + 1]
    return None


def format_section(prev_label, prev_ref, new_ref, compare):
    base = f"https://github.com/{UPSTREAM}"
    diff = f"{base}/compare/{prev_ref}...{new_ref}"
    commits = [c for c in compare.get("commits", []) if len(c.get("parents", [])) <= 1]
    total = compare.get("total_commits", len(commits))
    if not commits:
        return f"**What changed since {prev_label}:** no upstream code changes (rebuild only)."
    lines = [f"**What changed since {prev_label}** ({total} upstream commit{'s' if total != 1 else ''}, "
             f"[full diff]({diff}))"]
    for c in reversed(commits[-MAX_LINES:]):  # newest first
        subject = c["commit"]["message"].splitlines()[0].strip().replace("<", "&lt;")
        lines.append(f"- {subject} ([`{c['sha'][:7]}`]({base}/commit/{c['sha']}))")
    hidden = total - min(len(commits), MAX_LINES)
    if hidden > 0:
        lines.append(f"- ...and {hidden} older commit{'s' if hidden != 1 else ''} in the [full diff]({diff}).")
    return "\n".join(lines)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tag", required=True)
    ap.add_argument("--new-ref", required=True)
    ap.add_argument("--before")
    args = ap.parse_args()
    fork = os.environ["GITHUB_REPOSITORY"]

    prev = previous_fork_release(api(f"repos/{fork}/releases?per_page=100"), args.tag, args.before)
    if prev:
        prev_ref, prev_label = upstream_ref_for_tag(prev["tag_name"]), prev["name"] or prev["tag_name"]
    else:
        prev_ref = previous_upstream_release(api(f"repos/{UPSTREAM}/releases?per_page=100"), args.new_ref)
        prev_label = f"official {prev_ref}" if prev_ref else None
    if not prev_ref:
        print("**What changed:** first build, nothing earlier to compare with.")
        return
    try:
        compare = api(f"repos/{UPSTREAM}/compare/{prev_ref}...{args.new_ref}?per_page=250")
    except Exception as exc:  # notes are nice-to-have; never fail the release
        print(f"warning: compare {prev_ref}...{args.new_ref} failed: {exc}", file=sys.stderr)
        print(f"**What changed since {prev_label}:** see "
              f"[the upstream diff](https://github.com/{UPSTREAM}/compare/{prev_ref}...{args.new_ref}).")
        return
    print(format_section(prev_label, prev_ref, args.new_ref, compare))


if __name__ == "__main__":
    main()
