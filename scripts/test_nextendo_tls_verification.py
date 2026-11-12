#!/usr/bin/env python3
"""Assert that NEX DNS redirection never weakens TLS peer or hostname verification."""

from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: test_nextendo_tls_verification.py <eden-source-dir>")

source = Path(sys.argv[1]) / "src/core/hle/service/ssl/ssl_backend_openssl.cpp"
if not source.is_file():
    raise SystemExit(f"missing patched Eden OpenSSL backend: {source}")

text = source.read_text(encoding="utf-8")
start = text.find(
    "Result DoHandshake(std::span<const std::string> requested_alpn_protos) override {"
)
if start < 0:
    raise SystemExit("NEX redirected TLS handshake implementation was not applied")

end = text.find("Result Read(size_t* out_size", start)
if end < 0:
    raise SystemExit("could not isolate the patched TLS handshake method")

handshake = text[start:end]
required = {
    "peer certificate validation": "SSL_set_verify(ssl, SSL_VERIFY_PEER, nullptr);",
    "original hostname validation": "SSL_set1_host(ssl, redirected_host.c_str())",
    "forced verification state": "skip_cert_verification = false;",
    "post-handshake certificate result": "SSL_get_verify_result(ssl)",
    "redirected connections always verify": "if (redirected || !skip_cert_verification)",
}
for label, needle in required.items():
    if needle not in handshake:
        raise SystemExit(f"missing {label} in redirected TLS handshake")

forbidden = (
    "SSL_VERIFY_NONE",
    "SSL_set1_host(ssl, nullptr)",
    "SSL_set_verify_result(ssl, X509_V_OK)",
    "skip_cert_verification = true",
)
for needle in forbidden:
    if needle in handshake:
        raise SystemExit(f"insecure TLS override remains in redirected handshake: {needle}")

print("NEX redirected TLS peer and hostname verification: PASS")
