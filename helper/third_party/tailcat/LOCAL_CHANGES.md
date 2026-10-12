# Local Tailcat changes

Base: github.com/tailscale/tailcat v0.0.0-20260822200224-dd31c4938296.

This source copy retains the upstream BSD-3-Clause license. It adds SetRelayOnly to Server and Client, using magicsock.SetOnlyTCP443 before startup instead of TS_DEBUG_ALWAYS_USE_DERP's debug socket replacement. It also enables WantPeers in Status, fills the peer addresses from the configured network map, and exposes Client.Status.

Node and Disco keys are separate: the Disco private key is deterministically derived with HMAC-SHA256 using the `github.com/tailscale/tailcat disco key v1` domain and Curve25519 clamping. ConnBlob carries the server's Disco public key in CBOR field `k`; NewClient rejects missing, zero, or node-key-equal values before creating the engine. Hosts upgraded to this format must issue a new invitation token; one new token can still be shared among multiple clients.

Engine status changes now update endpoint candidates and trigger encrypted CallMeMaybe advertisements to known peers. Newly admitted peers and completed client meow handshakes also trigger an advertisement; sends happen after releasing the backend state lock and are suppressed in relay-only mode. `SetNetworkUp(true)` precedes key and DERP-map configuration. Concurrent meow snapshots and engine map application are serialized so an older peer map cannot overwrite a newer one. No global Go module cache is modified.

Client meow handshakes resend the idempotent DERP meow once per second within the existing 10-second timeout. DERP delivery is best-effort while either side is connecting; a retry lets the first join recover if the relay drops the initial packet or acknowledgment. Context cancellation remains the bound.

The upstream source contains its existing internal reflection helpers; the application does not use them for diagnostics or relay configuration. Re-check these changes when updating the pinned dependency.
