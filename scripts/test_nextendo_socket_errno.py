#!/usr/bin/env python3
"""Verify NEX's IPv6 fallback returns the Switch BSD EAFNOSUPPORT value."""

from __future__ import annotations

import re
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
    errno_enum = re.search(
        r"enum class Errno\\s*:\\s*u32\\s*\\{(?P<body>.*?)\\};",
        header,
        re.DOTALL,
    )
    if errno_enum is None:
        fail("Service::Sockets::Errno enum was not found")

    values = re.findall(r"\\bAFNOSUPPORT\\s*=\\s*(\\d+)\\s*,?", errno_enum.group("body"))
    # libnx nx/source/runtime/devices/convert_errno.c maps Switch BSD errno 97
    # to EAFNOSUPPORT. Do not substitute the host Linux errno value (97 is
    # coincidentally also Linux EAFNOSUPPORT) or the FreeBSD libc value (47).
    if values != ["97"]:
        fail(f"expected exactly AFNOSUPPORT = 97, found {values!r}")

    source = bsd_cpp.read_text(encoding="utf-8")
    socket_impl = re.search(
        r"std::pair<s32,\\s*Errno>\\s+BSD::SocketImpl\\s*\\([^)]*\\)\\s*\\{(?P<body>.*?)\\n\\}",
        source,
        re.DOTALL,
    )
    if socket_impl is None:
        fail("BSD::SocketImpl implementation was not found")
    body = socket_impl.group("body")
    if "domain != Domain::INET && domain != Domain::Unspecified" not in body:
        fail("IPv4/unspecified domain guard is missing")
    if "return {-1, Errno::AFNOSUPPORT};" not in body:
        fail("unsupported socket domains do not return EAFNOSUPPORT")

    print("NEX Switch BSD EAFNOSUPPORT value and IPv6 fallback verified.")


if __name__ == "__main__":
    main()
