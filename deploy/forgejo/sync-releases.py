#!/usr/bin/env python3
"""Copy GitHub release metadata and selected assets into a Forgejo mirror.

The repository's Git branches/tags must already be mirrored by Forgejo.
Only the newest N releases receive binary assets; all fetched release notes
are recreated so the Forgejo Releases page has a useful history.
"""
from __future__ import annotations

import json
import mimetypes
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

GITHUB_OWNER = os.environ.get("GITHUB_OWNER", "XDavi196")
GITHUB_REPO = os.environ.get("GITHUB_REPO", "NEX")
FORGEJO_API = os.environ.get("FORGEJO_API_URL", "http://forgejo:3000/api/v1").rstrip("/")
FORGEJO_OWNER = os.environ.get("FORGEJO_OWNER", "XDavi196")
FORGEJO_REPO = os.environ.get("FORGEJO_REPO", "NEX")
TOKEN = os.environ.get("FORGEJO_TOKEN", "").strip()
ASSET_RELEASES = max(0, int(os.environ.get("SYNC_ASSET_RELEASES", "8")))
TIMEOUT = 180


def request(url: str, *, method: str = "GET", payload=None, data=None, content_type=None, github=False, binary=False):
    headers = {"User-Agent": "NEX-Forgejo-Release-Sync/1.0"}
    if github:
        headers["Accept"] = "application/vnd.github+json"
    else:
        if not TOKEN:
            raise RuntimeError("FORGEJO_TOKEN is not configured")
        headers["Authorization"] = f"token {TOKEN}"
        headers["Accept"] = "application/json"
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if content_type:
        headers["Content-Type"] = content_type
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as response:
            raw = response.read()
            if not raw:
                return None
            if binary:
                return raw
            ctype = response.headers.get("Content-Type", "")
            if "json" in ctype:
                return json.loads(raw.decode("utf-8"))
            return raw
    except urllib.error.HTTPError as exc:
        detail = exc.read(2048).decode("utf-8", "replace")
        raise RuntimeError(f"{method} {url} returned HTTP {exc.code}: {detail}") from exc


def github_releases():
    results = []
    page = 1
    while page <= 20:
        url = (f"https://api.github.com/repos/{GITHUB_OWNER}/{GITHUB_REPO}"
               f"/releases?per_page=100&page={page}")
        batch = request(url, github=True) or []
        if not batch:
            break
        results.extend(x for x in batch if not x.get("draft"))
        if len(batch) < 100:
            break
        page += 1
    return results


def forgejo_releases():
    results = []
    for page in range(1, 51):
        url = (f"{FORGEJO_API}/repos/{urllib.parse.quote(FORGEJO_OWNER, safe='')}/"
               f"{urllib.parse.quote(FORGEJO_REPO, safe='')}/releases?limit=50&page={page}")
        batch = request(url) or []
        if not batch:
            break
        results.extend(batch)
        if len(batch) < 50:
            break
    return results

def forgejo_assets(release_id):
    url = (f"{FORGEJO_API}/repos/{urllib.parse.quote(FORGEJO_OWNER, safe='')}/"
           f"{urllib.parse.quote(FORGEJO_REPO, safe='')}/releases/{release_id}/assets")
    return request(url) or []


def create_release(item):
    path = (f"{FORGEJO_API}/repos/{urllib.parse.quote(FORGEJO_OWNER, safe='')}/"
            f"{urllib.parse.quote(FORGEJO_REPO, safe='')}/releases")
    body = {
        "tag_name": item["tag_name"],
        "target_commitish": item.get("target_commitish") or "main",
        "name": item.get("name") or item["tag_name"],
        "body": item.get("body") or "",
        "draft": False,
        "prerelease": bool(item.get("prerelease")),
    }
    return request(path, method="POST", payload=body)


def upload_asset(release_id, asset):
    name = os.path.basename(asset["name"])
    if not name or any(ch in name for ch in ('\r', '\n', '"')):
        raise RuntimeError(f"Unsafe release asset filename: {asset['name']!r}")
    download = request(asset["browser_download_url"], github=True, binary=True)
    if not isinstance(download, bytes):
        raise RuntimeError(f"Could not download {name}")
    boundary = "----NEXForgejo" + uuid.uuid4().hex
    mime = mimetypes.guess_type(name)[0] or "application/octet-stream"
    prefix = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="attachment"; filename="{name}"\r\n'
        f"Content-Type: {mime}\r\n\r\n"
    ).encode("utf-8")
    suffix = f"\r\n--{boundary}--\r\n".encode("ascii")
    body = prefix + download + suffix
    query_name = urllib.parse.quote(name, safe="")
    path = (f"{FORGEJO_API}/repos/{urllib.parse.quote(FORGEJO_OWNER, safe='')}/"
            f"{urllib.parse.quote(FORGEJO_REPO, safe='')}/releases/{release_id}/assets"
            f"?name={query_name}")
    request(path, method="POST", data=body,
            content_type=f"multipart/form-data; boundary={boundary}")
    print(f"  uploaded {name} ({len(download)} bytes)")


def main():
    if not TOKEN:
        print("FORGEJO_TOKEN is empty; waiting for Forgejo setup.")
        return 0

    source = github_releases()
    destination = forgejo_releases()
    by_tag = {r.get("tag_name"): r for r in destination}
    print(f"GitHub releases: {len(source)} | Forgejo releases: {len(destination)}")

    for index, item in enumerate(source):
        tag = item.get("tag_name")
        if not tag:
            continue
        current = by_tag.get(tag)
        if current is None:
            try:
                current = create_release(item)
                print(f"created {tag}")
            except RuntimeError as exc:
                # The pull mirror may not have fetched this tag yet; retry next cycle.
                print(f"waiting for mirrored tag {tag}: {exc}", file=sys.stderr)
                continue
        release_id = current.get("id")
        if not release_id:
            print(f"no Forgejo release id for {tag}; skipping assets", file=sys.stderr)
            continue

        # Copy binary assets only for the newest configured number of releases.
        if index >= ASSET_RELEASES:
            continue
        present = {a.get("name") for a in forgejo_assets(release_id)}
        for asset in item.get("assets", []):
            name = asset.get("name")
            if not name or name in present:
                continue
            try:
                upload_asset(release_id, asset)
            except Exception as exc:
                print(f"asset sync failed for {tag}/{name}: {exc}", file=sys.stderr)

    print("Release synchronization finished.")


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"release sync error: {exc}", file=sys.stderr)
        raise SystemExit(1)
