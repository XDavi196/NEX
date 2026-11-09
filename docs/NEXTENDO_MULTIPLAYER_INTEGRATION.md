# Nextendo multiplayer integration notes

This document describes the current NEX-side adaptation of the networking behavior implemented independently by DEN2 and Citron. It distinguishes code that is now connected to the guest networking stack from the service-side and peer-to-peer work that remains blocked or unverified.

## NEX identity and account boundary

NEX continues to use its own public OAuth client identity:

- Android application ID: `com.nex.nx`
- OAuth Client ID: `nxc_yAdR-14bOEq7`
- Redirect URI: `com.nex.nx://oauth/callback`
- Authorization Code + PKCE (S256); no client secret is embedded in the APK.

No DEN2/Citron Client ID, build secret, game token, private key or attestation material is reused. NEX supports an explicit user-selected import of DEN/Citron's nextendo_account.txt; the bearer session is validated with Nextendo and re-encrypted by Android Keystore. NEX continues using its own Client ID for all API requests and requests a fresh game token through that client.

The OAuth session, game-facing `nx2.` token, account friends API and OAuth presence heartbeat remain separate credential paths. An OAuth access token is not substituted for a game token.

## Client-side network path now connected

### Exact-host DNS and TLS

The account network settings accept the main Nextendo server IPv4 and a separate NAT-vantage IPv4 supplied by the service operator. Those values are encrypted at rest. Eden's guest DNS resolver redirects only explicitly configured hostnames and preserves the original canonical hostname. For each successful redirected resolution, NEX records the exact host/IP/service-port tuple in a bounded cache. BSD connect can recover the resolved IPv4 when a title later zeroes the destination address but keeps the service port.

At TLS handshake, NEX checks that the connected peer IP matches an exact configured redirect. It preserves the game's SNI when present and restores it from the DNS cache only when it is absent. Normal redirected service hosts negotiate HTTP/1.1; NPLN/gRPC hosts may retain only the game's requested `h2` or `http/1.1` protocols. Because the Nextendo virtual host serves its own certificate for the original Nintendo hostname, certificate verification is relaxed only for an exact, user-configured NEX redirect whose peer IP matches the route. All non-redirected connections retain the stock TLS verification. There is no wildcard DNS redirect or global trust-all TLS mode.

### NNCS observation from the real guest socket

The NEX overlay hooks Eden `BSD::RecvFromImpl`. It does not create an app-owned UDP socket to discover the player's public mapping, because such a socket would have a different NAT mapping from the socket used by the game.

The receive hook checks a 16-byte NNCS reply from the guest's UDP socket:

1. The remote source port must be `10025` or `10125`.
2. The big-endian test ID must be `101`; the returned external port must be in `1..65535`.
3. The external IPv4 must be public, not private/reserved.
4. Both replies must correspond to the same local guest UDP port, report the same external IPv4, and carry distinct responder IPv4 addresses.

Only after both vantage replies satisfy those checks does the native NAT state expose the observed external IPv4 to the Station URL rewriter. Any mismatch resets the pending observation. The two source ports and message layout follow the DEN2 client implementation; live SMM2 deployment compatibility still needs confirmation.

### DNS routing for the two NNCS NAT-check vantages

The native DNS cache now keeps the configured main server IPv4 and the distinct NAT-vantage IPv4 as separate routes. It maps only hostnames matching `nncs1-<label>.n.n.srv.nintendo.net` to the main server and `nncs2-<label>.n.n.srv.nintendo.net` to the NAT-vantage address. These constrained routes follow the DEN2/Nextendo resolver behavior without enabling a wildcard redirect for unrelated Nintendo domains. The resolved hostnames are retained for diagnostics and port-based endpoint recovery.

### UDP socket lifetime and Station URL correction

The existing native UDP port keeper parks an eligible unconnected UDP socket briefly when the guest closes it and re-adopts it when the game binds the same local port/address again. This preserves the host-side socket and its external NAT mapping across the short probe close/reopen sequence.

The SSL guest write path rewrites only recognized outbound WebSocket/PRUDP-Lite/RMC requests:

| RMC protocol | Method | Operation |
|---|---:|---|
| SecureConnection `0x0B` | `0x01` | Register |
| SecureConnection `0x0B` | `0x04` | RegisterEx |
| SecureConnection `0x0B` | `0x07` | ReplaceURL |
| MatchmakeExtension `0x6D` | `0x26` | Create |
| MatchmakeExtension `0x6D` | `0x27` | Join |
| MatchmakeExtension `0x6D` | `0x28` | AutoMatchmake |

Only a private station `address=` with non-zero `natf` is replaced; the station port and other parameters remain unchanged. RMC, PRUDP and WebSocket length fields are rebuilt. The modified TLS write buffer is retained unchanged across non-blocking write retries, and the emulated guest receives the original byte count when the rewritten frame is fully accepted.

This does not rewrite inbound peer Station URLs. Replacing a remote peer address with the local player's external IP would be wrong; peer station handling needs an authoritative per-peer address supplied or discovered through the supported service protocol.

### BSD compatibility for NPLN/gRPC

The NEX overlay now registers Eden BSD command IDs 29/30 as `RecvMMsg`/`SendMMsg`, using the same in/out buffer layout as DEN2. Messages are dispatched through the normal socket send/receive functions; a receive with a source-address buffer uses `RecvFromImpl`, so the guest-socket NNCS observer remains on the same path. The parser bounds the batch to 64 messages, 16 I/O vectors per message and 8 MiB of payload to reject malformed guest buffers safely.

The previously stubbed `EventFd` command creates a loopback UDP self-pipe; its `Write` enqueues 64-bit values and its `Read` drains and sums them, while only the first read may block. This gives gRPC/NPLN runtimes a pollable wake-up descriptor instead of a fake fd. BSD `Select` is no longer a zero-result stub: the HLE parses the read/write/exception descriptor sets, waits through the native poller and writes the ready sets back to the guest. IPv6 socket creation returns `EAFNOSUPPORT` cleanly so an IPv4 fallback can proceed. Socket options unsupported by Eden are stored per descriptor and echoed by `getsockopt`; supported `SOL_SOCKET` options still reach the host socket.

The Station URL rewriter also recognizes `SecureConnection.RegisterEx` (`0x0B/0x04`) in addition to Register/ReplaceURL and SMM2 Create/Join/AutoMatchmake. All station rewriting remains limited to private addresses with non-zero `natf`, after two valid NNCS vantage replies agree on the public mapping.

These are emulator-side compatibility primitives, not proof of a working title session. Build #407 has passed the overlay and unit-test stages and is still in native Android APK compilation; the newer source revision is queued for its own build. Live NPLN validation and a two-device game test are still required.
 
### Friends and presence

The account-facing friends and presence features remain separate from gameplay transport. Friends operations use the server-issued `nx2.` game token, while presence publishing uses an OAuth access token with the granted presence scope. The NEX Client ID is unchanged.

## What is not complete or not verified

- **BAAS / ACC / LoginEx:** the Nextendo founder explicitly rejected client-generated keys, third-party RS256 BAAS JWTs and per-installation JWKS registration. NEX does not enable DEN2's local signing fallback. A server-issued or otherwise officially approved NEX game-authentication credential is required before the game can complete LoginEx and obtain its secure ticket.
- **SMM2 production service:** the live auth/secure endpoints, certificate routing, virtual station parameters, and exact NNCS deployment contract still need confirmation.
- **Matchmaking:** the standalone NEX `NextendoSmm2SessionClient` is a testable auth/secure-session harness; it is not wired into the game's native HLE/network lifecycle. Per-title RMC room create/join/leave and session-state operations remain to be connected.
- **P2P:** peer discovery/signaling, periodic NAT keepalives, actual UDP hole punching, peer cleanup, and two-device testing remain outstanding.
- **Server readiness:** a successful local unit test or APK build does not establish that the deployed SMM2 matchmaking service is enabled or working.

The separate Kotlin `NextendoNatProbeClient` is diagnostic-only. It must not be used to configure the public address for the emulated game, because its UDP socket is not the guest game's socket.

## Verification criteria for claiming multiplayer

Do not describe NEX as multiplayer-ready until all of the following have been tested with two independent NEX accounts and two devices using a Nextendo-supported game version:

1. Both accounts obtain an officially issued game credential through the NEX-approved auth flow.
2. The guest game completes the real TicketGranting.LoginEx and secure PRUDP handshake.
3. The deployed game server successfully creates, discovers, joins and leaves a matchmaking session.
4. Both guests receive consistent NNCS observations for their own game UDP sockets.
5. Peer signaling and UDP hole punching complete, gameplay packets flow in both directions, and disconnect/cleanup works.

