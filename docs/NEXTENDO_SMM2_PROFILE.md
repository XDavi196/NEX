# NEX — Super Mario Maker 2 Nextendo profile

Updated: 7 November 2026.

This document separates values confirmed in the public SMM2 server source from deployment values that must be discovered at runtime. It does not claim that NEX is already connected to the live service.

## BAAS id_token for NEX

The public Nextendo core confirms the login placement:

1. NEX obtains a fresh `nx2.` game token from the documented game-token endpoint. The OAuth access token is not a game token.
2. NEX signs a compact BAAS `id_token` with RS256.
3. The JWT payload contains the string claim `nnex`, whose value is the exact server-issued `nx2.` token.
4. The JWS header carries the provisioned `kid` and HTTPS `jku`; the matching public JWK must be registered with the BAAS verification service.
5. The JWT is the `Token` field in a NEX `AuthenticationInfo` DataHolder. The DataHolder is sent as the raw `extraData` after the LoginEx username NEX String.

The current NEX implementation now has:
- `NextendoBaasIdTokenBuilder`: RS256 compact-JWT construction using a runtime signer, registered `kid`, HTTPS `jku`, and externally provisioned `iss`, `aud` and `sub`.
- `NextendoBaasAuthenticationInfo`: NEX DataHolder serialization for `AuthenticationInfo`, including NGSVersion 3, TokenType 1 and ServerVersion 40000.
- `NextendoBaasRuntime`: connects the builder to Eden's ACC cache only when a NEX-specific signing provider is installed; it clears the cache for missing/expired tokens, absent provider, logout or invalid claims.
- The Eden overlay adds a bounded native cache, JNI methods and application ACC handlers. ACC serves the actual compact JWT or returns a byte-count of zero. It no longer returns a dummy 256-byte zero buffer.
- Unit tests cover JWT shape, the `nnex` binding and AuthenticationInfo serialization.

**Still not live:** the NEX-specific RSA private key or approved signing provider, public JWK registration, `kid`, JKU, issuer/audience/subject mapping and any required client attestation have not been provisioned. No provider is installed in the current branch, so no production BAAS JWT is sent to ACC.

Do not put the BAAS JWT in the PRUDP CONNECT payload. The JWT belongs in `LoginEx.extraData`. The later secure PRUDP CONNECT carries the server ticket and encrypted check request returned from LoginEx.

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

- Provision the NEX BAAS signer, matching public JWK/JWKS URL, key ID, issuer, audience, subject/PID mapping and any required client-attestation material, then install the provider in the NEX runtime.
- Confirm the live SMM2 auth hostname, DNS destination, TLS certificate coverage/SNI and negotiated virtual PRUDP station parameters.
- Connect the guest's actual DNS/socket path to those runtime values; validate ACC cache delivery and guest LoginEx with the provisioned token.
- Validate the parsed LoginEx ticket and secure CONNECT against the live service. The local codecs already have synthetic wire-format tests.
- Integrate the session lifecycle into the guest's network path, implement title-specific RMC methods and verify matchmaking.
- Implement live NNCS/NAT sockets, UDP mapping preservation/keepalives, peer discovery, P2P hole-punch and cleanup.
- Test using two NEX accounts on two Android devices in a game whose live service reports matchmaking as supported.

The profile and serializers are code-level progress. They do not establish that the production endpoint accepts NEX or that Super Mario Maker 2 multiplayer works. A complete test still requires two NEX accounts, two devices and an actual in-game match/session.


## Protocol implementation boundary

The ticket codec consumes the one-time source key returned by the LoginEx response to decrypt the outer ClientTicket and recover the session key plus opaque inner server ticket. It does not derive or contain the secure server's Kerberos password. The secure CONNECT payload encrypts the PID/CID/check tuple with the recovered session key and validates the server's incremented check reply.

The auth/secure WSS coordinator takes an explicit original auth hostname and negotiated PRUDP station values. It preserves TLS hostname verification and follows the actual secure StationURL returned by LoginEx; it contains no guessed production host/IP, server password or attestation key. It is protocol code and is not yet wired into the guest game's own socket lifecycle.


## AuthenticationInfo wire-format verification

The DataHolder is encoded as the NEX String `AuthenticationInfo` followed by exactly one NEX Buffer. The Buffer body is a version-1 NEX Structure: one version byte, one little-endian u32 content length, then the structure fields (`Token` NEX String, `NGSVersion` u32, `TokenType` u8 and `ServerVersion` u32). This matches the public Nextendo test helper's `String("AuthenticationInfo")` + `Buffer(body)` and the public NEX `WriteStructure` implementation. The former extra outer u32 length has been removed. The LoginEx RMC request then appends this complete DataHolder as raw `extraData` immediately after the username NEX String.
