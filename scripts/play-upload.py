#!/usr/bin/env python3
"""Upload an Android App Bundle to a Google Play testing track and commit it.

Used by .github/workflows/play.yml. Reads a short-lived OAuth token (androidpublisher scope) from
GOOGLE_PLAY_ACCESS_TOKEN and never prints it. Creates an edit, uploads the bundle, replaces the track's release
with the new version (with its R8 mapping when --mapping is given), validates and commits. A failure leaves the edit uncommitted and exits nonzero.

  scripts/play-upload.py --package social.hotmess.android.app --bundle app-release.aab \
    --mapping mapping.txt --track internal
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

API = "https://androidpublisher.googleapis.com/"


class PlayError(Exception):
    def __init__(self, method, code, body):
        super().__init__(f"Google Play {method} failed (HTTP {code}): {body}")
        self.code = code


def play(token, method, path, payload=None, bundle=None):
    headers = {"Authorization": "Bearer " + token}
    if bundle:
        data, headers["Content-Type"] = bundle.read_bytes(), "application/octet-stream"
    else:
        data, headers["Content-Type"] = (json.dumps(payload).encode() if payload is not None else None), "application/json"
    request = urllib.request.Request(API + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=600) as response:
            content = response.read()
            return json.loads(content) if content else {}
    except urllib.error.HTTPError as error:
        raise PlayError(method, error.code, error.read().decode()) from None


def summary(text):
    print(text)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as file:
            file.write(text + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--package", required=True)
    parser.add_argument("--bundle", required=True, type=Path)
    parser.add_argument("--mapping", type=Path, help="R8 mapping.txt, so Play shows readable stack traces")
    parser.add_argument("--track", default="internal", choices=["internal", "alpha", "beta"])
    parser.add_argument("--release-name", help="defaults to the bundle's version code")
    args = parser.parse_args()
    token = os.environ.get("GOOGLE_PLAY_ACCESS_TOKEN")
    if not token:
        sys.exit("Set GOOGLE_PLAY_ACCESS_TOKEN")

    base = f"androidpublisher/v3/applications/{args.package}/edits"
    try:
        edit = play(token, "POST", base, {})["id"]
    except PlayError as error:
        if error.code == 404:
            sys.exit(f"{args.package} isn't in Play Console, or the service account has no access to it")
        raise
    try:
        version = play(token, "POST", f"upload/{base}/{edit}/bundles?uploadType=media", bundle=args.bundle)["versionCode"]
        if args.mapping:
            play(token, "POST", f"upload/{base}/{edit}/apks/{version}/deobfuscationFiles/proguard?uploadType=media",
                 bundle=args.mapping)
        release = {"name": args.release_name or str(version), "versionCodes": [str(version)], "status": "completed"}
        play(token, "PUT", f"{base}/{edit}/tracks/{args.track}", {"track": args.track, "releases": [release]})
        play(token, "POST", f"{base}/{edit}:validate", {})
        play(token, "POST", f"{base}/{edit}:commit", {})
    except Exception:
        print(f"Upload failed; the Play edit {edit} was left uncommitted.", file=sys.stderr)
        raise
    mapped = " with its R8 mapping" if args.mapping else ""
    summary(f"Committed {args.package} version {version} ({release['name']}){mapped} to the Play {args.track} track.")


if __name__ == "__main__":
    main()
