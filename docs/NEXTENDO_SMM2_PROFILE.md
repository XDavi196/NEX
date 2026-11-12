# NEX — Super Mario Maker 2 Nextendo profile

Updated: 8 November 2026.

This document separates values confirmed in the public SMM2 server source from deployment values that must be discovered at runtime. It does not claim that NEX is already connected to the live service.

## BAAS and LoginEx readiness

The official Developer Documentation confirms that the OAuth access token is not a game credential: NEX must request `game.matchmaking`, obtain the server-issued `nx2.` token, and bind that exact token as the string-valued `nnex` claim in the BAAS token used for LoginEx. The public [Nextendo NEX core](https://github.com/NextendoNetwork/nextendo-nex/blob/main/authbinding.go) confirms extraction of `nnex` from a JWT embedded in LoginEx extraData.

This does not establish a supported client-side signing contract. The public contract does not define a third-party NEX issuer/audience/subject mapping, accepted signing key, expiry policy, JWK registration or application-attestation procedure. Nextendo's retired BAAS registration proposal explicitly says third-party client-generated RS256 tokens and per-installation JWK registration are not accepted.

Accordingly, NEX has removed the client-side JWT builder, PEM/JWK import and Citron/DEN signing paths. `NextendoBaasRuntime` clears Eden's ACC cache and reports `nex_baas_contract_not_provisioned` while the official NEX flow is unavailable. The native ACC bridge is retained as an empty fail-closed boundary; it does not fabricate a token or substitute an OAuth access token.

`NextendoSmm2SessionClient` remains a protocol probe that requires caller-supplied AuthenticationInfo bytes, original auth hostname and negotiated PRUDP station parameters. It is not wired to the guest game's socket lifecycle and cannot complete LoginEx without the approved BAAS credential. No two-device SMM2 login has been verified.

## Public SMM2 client profile

The NEX Android source now records the following values from the public \`NextendoNetwork/super-mario-maker-2\` server source:

| Parameter | Public SMM2 value |
|---|---:|
| NEX version | \`40000\` |
| PRUDP-Lite title access key | \`fdf6617f\` |
| Auth WSS port | \`443\` |
| Secure WSS port in the public server's default configuration | \`60007\` |
| Auth PRUDP minor version | \`5\` |
| Secure PRUDP minor version | \`0\` |
| Fragment size | \`1300\` bytes |

\`fdf6617f\` is the public per-title PRUDP access key, not an account password or a private NEX credential. It is used only for the PRUDP-Lite CONNECT proof. It must not be confused with the secure server's Kerberos password or internal deployment secrets; those remain server-side and are not copied into NEX.

The auth connection uses WSS on port 443. The secure endpoint is advertised to the client by the \`StationURL\` returned from LoginEx, in the public SMM2 server's \`prudps:/address=...;port=...\` format. The server can override the secure port at deployment time, so NEX parses and follows the advertised address and port. \`60007\` is the public source's default, not a safe fallback when the response is missing.

\`NextendoSmm2Profile\` builds WSS endpoints while retaining the supplied hostname for TLS hostname verification/SNI, and parses the secure StationURL. The destination hostname/IP and DNS mapping are still deployment-specific and have not been confirmed for the live Nextendo deployment. TLS certificate checks remain enabled.

## Public auth hostname mapping

The public game-server profile associates the SMM2 PRUDP title key `fdf6617f` with game-server ID `22306D00`. The Switch NEX auth hostname pattern yields `g22306D00-lp1.s.n.srv.nintendo.net`. NEX records this as `AUTH_HOSTNAME` and preserves it in the WSS URI for TLS SNI. This is only the hostname: the public repository does not publish the production Nextendo IPv4 destination, live certificate chain, or the NAT vantage address. The exact-host resolver therefore remains unconfigured until runtime infrastructure values are supplied.

## What the public SMM2 server currently demonstrates

The public Nextendo SMM2 server source includes DataStore handlers for the player's profile and user list, including `sync_user_profile(49)` and `get_users(48)`, which support Course World identity initialization. The public compatibility registry still marks matchmaking and presence as untested. NEX must not invent RMC method IDs, room payloads, or NAT probe ports to fill that gap; the server implementation must expose and verify the matching methods first.

## What remains before an SMM2 session can start

- Obtain a Nextendo-approved NEX game-authentication flow or server-issued BAAS id_token, with its exact claims, expiry, PID binding, signature and attestation contract. Do not generate a client JWK or sign a token locally.
- Confirm the live SMM2 auth hostname, DNS destination, TLS certificate coverage/SNI and negotiated virtual PRUDP station parameters.
- Connect the guest's actual DNS/socket path to those runtime values; validate ACC cache delivery and guest LoginEx with the provisioned token.
- Validate the parsed LoginEx ticket and secure CONNECT against the live service. The local codecs already have synthetic wire-format tests.
- Integrate the session lifecycle into the guest's network path, implement title-specific RMC methods and verify matchmaking.
- Supply and verify the two SMM2 NNCS endpoints/ports and the deployed 16-byte probe contract. NEX now has a one-shot two-vantage UDP probe client, but it requires explicit runtime endpoints and is not called by the guest game's network lifecycle.
- Integrate probe results with the guest's NAT lifecycle, then implement periodic keepalives, peer discovery, matchmaking signaling, P2P hole-punch and cleanup.
- Test using two NEX accounts on two Android devices in a game whose live service reports matchmaking as supported.

The profile and serializers are code-level progress. They do not establish that the production endpoint accepts NEX or that Super Mario Maker 2 multiplayer works. A complete test still requires two NEX accounts, two devices and an actual in-game match/session.


## Protocol implementation boundary

The ticket codec consumes the one-time source key returned by the LoginEx response to decrypt the outer ClientTicket and recover the session key plus opaque inner server ticket. It does not derive or contain the secure server's Kerberos password. The secure CONNECT payload encrypts the PID/CID/check tuple with the recovered session key and validates the server's incremented check reply.

The auth/secure WSS coordinator takes an explicit original auth hostname and negotiated PRUDP station values. It preserves TLS hostname verification and follows the actual secure StationURL returned by LoginEx; it contains no guessed production host/IP, server password or attestation key. It is protocol code and is not yet wired into the guest game's own socket lifecycle.


## AuthenticationInfo wire-format verification

The DataHolder is encoded as the NEX String `AuthenticationInfo` followed by exactly one NEX Buffer. The Buffer body is a version-1 NEX Structure: one version byte, one little-endian u32 content length, then the structure fields (`Token` NEX String, `NGSVersion` u32, `TokenType` u8 and `ServerVersion` u32). This matches the public Nextendo test helper's `String("AuthenticationInfo")` + `Buffer(body)` and the public NEX `WriteStructure` implementation. The former extra outer u32 length has been removed. The LoginEx RMC request then appends this complete DataHolder as raw `extraData` immediately after the username NEX String.


## Current online admission and service status — 8 November 2026

The public Nextendo compatibility tracker currently marks SMM2 **Experimental / In preparation** and explicitly lists matchmaking and presence as **Untested**, for version **3.0.3** ([official tracker](https://wiki.nextendo.network/games)). Use 3.0.3 for controlled SMM2 client tests; a tested title version is not evidence that matchmaking is implemented or available.

Before attempting game login, the account must have a verified email, a Discord account linked through the official verification flow and membership in the Nextendo Discord. The account must not already be online from another emulator/device. The official [Discord linking guide](https://wiki.nextendo.network/guide/discord-link) identifies missing Discord linkage as a common rejection reason; the [security guide](https://wiki.nextendo.network/guide/account-security) explains how to revoke stale sessions.

NEX does not generate a BAAS signing key or register a public JWK. Nextendo must publish a supported server-issued or approved NEX-specific game-authentication contract, including exact claims, PID binding, expiry and any required application attestation. Until then, NEX deliberately sends no BAAS token through ACC. No DEN/Citron credential, key, token or session data is imported.

The public SMM2 service code and local PRUDP/RMC test harness do not provide a verified end-to-end matchmaking contract. Do not invent room methods, payloads, secure-server addresses or NNCS probe endpoints. The next online milestone is a service-confirmed BAAS registration plus a real guest LoginEx/secure connection; matchmaking and two-device tests remain gated by the server's published support status.
