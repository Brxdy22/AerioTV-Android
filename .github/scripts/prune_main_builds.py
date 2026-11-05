#!/usr/bin/env python3
"""Print the release tags to delete so each app version shows as one release.

Keeps the newest fireos6-main-* build of each version, for the newest
KEEP_VERSIONS versions. Also removes a fireos6-v* build of a version that has a
kept main build. Never removes the last main build of a version on its own, as
the LAN updater needs a main build to install.
"""
import json
import re
import sys

KEEP_VERSIONS = 5
MAIN_TAG = re.compile(r"fireos6-main-[0-9a-f]{7}")
RELEASE_TAG = re.compile(r"fireos6-v([0-9]+(?:\.[0-9]+)*)")
TITLE = re.compile(r"AerioTV-Brxdy22Fork ([0-9]+(?:\.[0-9]+)*)(?:-[0-9]+)?")


def to_delete(releases, keep_versions=KEEP_VERSIONS):
    builds = [r for r in releases if MAIN_TAG.fullmatch(r.get("tag_name", ""))]
    builds.sort(key=lambda r: r.get("published_at") or "", reverse=True)
    kept, doomed = [], []
    for release in builds:
        match = TITLE.fullmatch(release.get("name") or "")
        if match is None:  # Unknown title: leave it for a person to look at.
            continue
        version = match.group(1)
        if version in kept or len(kept) >= keep_versions:
            doomed.append(release["tag_name"])
        else:
            kept.append(version)
    for release in releases:
        match = RELEASE_TAG.fullmatch(release.get("tag_name", ""))
        if match and match.group(1) in kept:
            doomed.append(release["tag_name"])
    return doomed


if __name__ == "__main__":
    with open(sys.argv[1], encoding="utf-8") as handle:
        print("\n".join(to_delete(json.load(handle))))
