#!/usr/bin/env python3
"""Generate per-APK update manifests for push-triggered dev builds.

Mirrors the manifest shape used by the manual Android Release workflow so
in-app update checks can consume both, with channel "dev".

Environment variables:
  DEV_TAG               release tag, e.g. v5.6.0-dev.202611261540
  DEV_VERSION           APK versionName, e.g. 5.6.0-dev.4e30ffa
  VERSION_CODE          versionCode parsed from app/build.gradle
  SHORT_SHA             short commit hash
  RELEASE_DOWNLOAD_BASE GitHub release download URL prefix
  NOTES_FILE            path to build/release-notes.md (optional)
"""

import glob
import hashlib
import json
import os


def main():
    tag = os.environ["DEV_TAG"]
    version_name = os.environ["DEV_VERSION"]
    version_code = int(os.environ["VERSION_CODE"])
    short_sha = os.environ["SHORT_SHA"]
    base = os.environ["RELEASE_DOWNLOAD_BASE"]

    notes = ""
    notes_path = os.environ.get("NOTES_FILE", "")
    if notes_path and os.path.isfile(notes_path):
        with open(notes_path, encoding="utf-8") as fh:
            notes = fh.read().strip()

    for apk in sorted(glob.glob("dist/*.apk")):
        name = os.path.splitext(os.path.basename(apk))[0]
        with open(apk, "rb") as fh:
            content = fh.read()
        data = {
            "name": tag,
            "versionName": version_name,
            "desc": "{} - {}".format(tag, short_sha),
            "channel": "dev",
            "code": version_code,
            "apk": "{}/{}.apk".format(base, name),
            "size": len(content),
            "sha256": hashlib.sha256(content).hexdigest(),
            "downloads": {
                "github": {"url": "{}/{}.apk".format(base, name)},
            },
        }
        if notes:
            data["notes"] = notes
        with open("dist/{}.json".format(name), "w", encoding="utf-8") as fh:
            json.dump(data, fh, ensure_ascii=False, indent=2)
            fh.write("\n")
        print("Wrote dist/{}.json".format(name))


if __name__ == "__main__":
    main()
