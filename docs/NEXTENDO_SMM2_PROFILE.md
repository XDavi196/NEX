# NEX — Super Mario Maker 2 Nextendo profile

Updated: 7 November 2026.

This document separates values confirmed in the public SMM2 server source from deployment values that must be discovered at runtime. It does not claim that NEX is already connected to the live service.

## BAAS id_token for NEX

The public Nextendo core confirms the login placement:

1. NEX obtains a fresh \`nx2.\` game token from the documented game-token endpoint. The OAuth access token is not a game token.
2. NEX signs a compact BAAS \`id_token\` with RS256.
3. The JWT payload contains the string claim \`nnex\`, whose value is the exact server-issued \`nx2.\` token. The public server also recognizes an optional \`tv\` claim for the installed title-update version.
4. The public BAAS-JWKS contract uses the JWS \`jku\` header to locate the public key set and the \`kid\` header to select the matching RSA key.
5. The JWT is the \`Token\` field in a NEX \`AuthenticationInfo\` DataHolder. The DataHolder is sent in the \`extraData\` of TicketGranting \`LoginEx\`.

The current NEX implementation now has:
- \`NextendoBaasIdTokenBuilder\`: RS256 compact-JWT construction using a runtime signer, registered \`kid\`, HTTPS \`jku\`, and externally provisioned \`iss\`, \`aud\` and \`sub\`. It sets \`hm=true\` and reserves \`nnex\` for the current \`nx2.\` token.
- \`NextendoBaasAuthenticationInfo\`: NEX DataHolder serialization for \`AuthenticationInfo\`, including the NEX String token and SMM2 server profile fields (\`NGSVersion=3\`, \`TokenType=1\`, \`ServerVersion=40000\`).
- Unit tests for JWT construction, the \`nnex\` token binding, and the encoded LoginEx extraData structure.

**Not yet live:** the NEX-specific RSA private key, public JWK registration, key ID, JKU URL, issuer/audience/subject mapping and any required client-attestation signer have not been provisioned for NEX. The builder has no production signer and the result is not wired into Eden ACC or the real LoginEx path.

Do not put the BAAS JWT in the PRUDP CONNECT payload. The JWT belongs in \`LoginEx.extraData\`. The later secure PRUDP CONNECT carries a Kerberos server ticket and encrypted check request, which NEX does not yet derive from a completed LoginEx response.

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

## What remains before an SMM2 session can start

- Provision the NEX BAAS RSA key and register its matching public JWK at the HTTPS \`jku\` URL with the same \`kid\`.
- Supply the exact NEX BAAS issuer, audience, subject/PID mapping and any device/client-attestation configuration.
- Identify the original SMM2 auth hostname and the service IP/DNS routing; preserve the title hostname for TLS SNI and certificate validation.
- Connect Eden ACC to the BAAS builder and \`AuthenticationInfo\` DataHolder encoder, then validate the actual \`LoginEx\` request.
- Consume the live secure \`StationURL\` from the LoginEx response and derive the secure CONNECT payload from the server ticket plus the encrypted check request.
- Wire the SMM2-specific auth/secure PRUDP sessions into Eden's title lifecycle, then implement and validate the required RMC methods, matchmaking and NAT/P2P lifecycle.

The profile and serializers are code-level progress. They do not establish that the production endpoint accepts NEX or that Super Mario Maker 2 multiplayer works. A complete test still requires two NEX accounts, two devices and an actual in-game match/session.
