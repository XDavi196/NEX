#!/usr/bin/env python3
"""Check NEX's patched IFriendService method IDs against the pinned IPC contract."""

from __future__ import annotations

import re
import sys
from pathlib import Path


def fail(message: str) -> None:
    raise SystemExit(f"Nextendo friend IPC contract check failed: {message}")


def main() -> None:
    if len(sys.argv) != 2:
        fail("usage: test_nextendo_friend_ipc.py <eden-source-root>")

    friend_cpp = Path(sys.argv[1]) / "src/core/hle/service/friend/friend.cpp"
    if not friend_cpp.is_file():
        fail(f"patched Eden friend.cpp was not found: {friend_cpp}")

    source = friend_cpp.read_text(encoding="utf-8")

    def entry(command_id: int) -> tuple[str, str]:
        pattern = re.compile(
            rf"\{{\s*{command_id}\s*,\s*([^,]+),\s*\"([^\"]+)\"\s*\}}"
        )
        matches = pattern.findall(source)
        if len(matches) != 1:
            fail(f"expected one IFriendService entry for command {command_id}, found {len(matches)}")
        handler, name = matches[0]
        return handler.strip(), name

    expected = {
        10100: ("&IFriendService::GetFriendListIds", "GetFriendListIds"),
        10102: ("&IFriendService::UpdateFriendInfo", "UpdateFriendInfo"),
        10500: ("&IFriendService::GetProfileList", "GetProfileList"),
        10501: ("&IFriendService::GetProfileListV2", "GetProfileListV2"),
        20107: ("&IFriendService::GetFriendDetailedInfoV2", "GetFriendDetailedInfoV2"),
        20108: ("&IFriendService::GetFriendListForViewerV2", "GetFriendListForViewerV2"),
        20109: ("nullptr", "UpdateFriendInfoForViewerV2"),
        20111: ("nullptr", "LoadFriendSettingV2"),
        20601: ("&IFriendService::GetUserPresenceViewV2", "GetUserPresenceViewV2"),
    }
    for command_id, wanted in expected.items():
        actual = entry(command_id)
        if actual != wanted:
            fail(f"command {command_id} maps to {actual!r}, expected {wanted!r}")

    for handler in (
        "GetFriendListIds",
        "UpdateFriendInfo",
        "GetProfileList",
        "GetProfileListV2",
        "GetFriendDetailedInfoV2",
        "GetFriendListForViewerV2",
        "GetUserPresenceViewV2",
    ):
        if not re.search(rf"\bvoid\s+{handler}\s*\(HLERequestContext&\s+ctx\)", source):
            fail(f"registered handler body {handler} is missing")

    if "GetFriendDetailListV2" in source or "GetFriendPresenceV2" in source:
        fail("an unverified handler is still registered or compiled")

    print("Nextendo IFriendService command IDs and handler registrations verified.")


if __name__ == "__main__":
    main()
