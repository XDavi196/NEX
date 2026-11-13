#!/usr/bin/env python3
"""Guard NEX's credential boundary: own PKCE sessions only, BAAS fail-closed."""

from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
base = root / "integration/android/src/main/java/com/nex/nx/nextendo"
auth = (base / "NextendoAuthManager.kt").read_text(encoding="utf-8")
store = (base / "NextendoSessionStore.kt").read_text(encoding="utf-8")
runtime = (base / "NextendoBaasRuntime.kt").read_text(encoding="utf-8")
profiles = (base / "NextendoGameNetworkProfiles.kt").read_text(encoding="utf-8")
overlay = (root / "scripts/apply-eden-overlay.py").read_text(encoding="utf-8")

for label, text, forbidden in (
    ("auth manager", auth, (
        "importDenAccountText",
        "NextendoAccountImportActivity",
        "startCitronBaasKeyImport",
        "NextendoBaasKeyImportActivity",
        "NextendoAccountTxtCodec",
    )),
    ("session store", store, (
        "importDenAccountFile",
        "saveBaasSigningKeyPem",
        "loadBaasSigningKeyPem",
        "NextendoAccountTxtCodec",
    )),
    ("BAAS runtime", runtime, (
        "NextendoCitronBaas",
        "NextendoBaasIdTokenBuilder",
        "signRs256",
        "PEM",
        "jku",
        "RS256",
    )),
    ("Eden overlay", overlay, (
        "NextendoAccountImportActivity",
        "NextendoBaasKeyImportActivity",
        "Importar chave BAAS",
        "startCitronBaasKeyImport",
    )),
):
    for needle in forbidden:
        if needle in text:
            raise SystemExit(f"{label} reintroduces forbidden credential path: {needle}")

if "currentProvider(): NextendoBaasTokenProvider? = null" not in runtime:
    raise SystemExit("BAAS provider must remain uninstalled until Nextendo authorizes NEX.")
if "NextendoNativeBridge.clearBaasIdToken()" not in runtime:
    raise SystemExit("BAAS runtime must clear the guest-visible ACC token cache.")

if "NextendoCitronBaasProfile" in profiles or "baasHosts" in profiles:
    raise SystemExit("DNS routing must not depend on a removed or cross-emulator BAAS profile.")

for filename in (
    "NextendoAccountImportActivity.kt",
    "NextendoAccountTxtCodec.kt",
    "NextendoBaasKeyImportActivity.kt",
    "NextendoCitronBaasProfile.kt",
    "NextendoCitronBaasProvider.kt",
    "NextendoCitronBaasSigner.kt",
    "NextendoBaasIdTokenBuilder.kt",
    "NextendoBaasAuthenticationInfo.kt",
    "NextendoBaasJwk.kt",
    "NextendoBaasJwksVerifier.kt",
    "NextendoBaasRegistrationRequest.kt",
):
    if (base / filename).exists():
        raise SystemExit(f"obsolete credential source remains: {filename}")

print("NEX credential isolation and fail-closed BAAS: PASS")
