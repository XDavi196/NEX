#!/usr/bin/env python3
"""Verify NEX's IPv6 fallback returns the Switch BSD EAFNOSUPPORT value."""

from __future__ import annotations

import sys
from pathlib import Path


def fail(message: str) -> None:
    raise SystemExit(f"NEX socket errno ABI check failed: {message}")


def main() -> None:
    if len(sys.argv) != 2:
        fail("usage: test_nextendo_socket_errno.py <eden-source-root>")

    root = Path(sys.argv[1])
    socket_header = root / "src/core/hle/service/sockets/sockets.h"
    bsd_cpp = root / "src/core/hle/service/sockets/bsd.cpp"
    if not socket_header.is_file() or not bsd_cpp.is_file():
        fail("pinned Eden socket sources are missing")

    header = socket_header.read_text(encoding="utf-8")
    enum_anchor = "enum class Errno : u32 {"
    if header.count(enum_anchor) != 1:
        fail("Service::Sockets::Errno enum was not found exactly once")
    enum_body = header.split(enum_anchor, 1)[1].split("};", 1)[0]
    errno_entries = [
        line.strip() for line in enum_body.splitlines() if "AFNOSUPPORT" in line
    ]
    # libnx nx/source/runtime/devices/convert_errno.c maps Switch BSD errno 97
    # to EAFNOSUPPORT. The NEX guest-facing enum must use that ABI value.
    if errno_entries != ["AFNOSUPPORT = 97,"]:
        fail(f"expected exactly AFNOSUPPORT = 97, found {errno_entries!r}")

    source = bsd_cpp.read_text(encoding="utf-8")
    function_anchor = "std::pair<s32, Errno> BSD::SocketImpl("
    if source.count(function_anchor) != 1:
        fail("BSD::SocketImpl implementation was not found exactly once")
    body = source.split(function_anchor, 1)[1].split("\n}", 1)[0]
    if "domain != Domain::INET && domain != Domain::Unspecified" not in body:
        fail("IPv4/unspecified domain guard is missing")
    if "return {-1, Errno::AFNOSUPPORT};" not in body:
        fail("unsupported socket domains do not return EAFNOSUPPORT")

    print("NEX Switch BSD EAFNOSUPPORT value and IPv6 fallback verified.")


if __name__ == "__main__":
    main()
