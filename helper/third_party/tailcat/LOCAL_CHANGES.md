# Local Tailcat changes

Base: github.com/tailscale/tailcat v0.0.0-20260822200224-dd31c4938296.

This source copy retains the upstream BSD-3-Clause license. It adds SetRelayOnly to Server and Client, using magicsock.SetOnlyTCP443 before startup instead of TS_DEBUG_ALWAYS_USE_DERP's debug socket replacement. It also enables WantPeers in Status, fills the peer addresses from the configured network map, and exposes Client.Status. Initial peer endpoint candidates are exchanged using Disco-encrypted CallMeMaybe messages over DERP, allowing magicsock to start UDP path probing without exposing endpoint addresses to the relay. Both endpoints use node-derived disco keys for this encrypted bootstrap. No global Go module cache is modified.

The upstream source contains its existing internal reflection helpers; the application does not use them for diagnostics or relay configuration. Re-check these changes when updating the pinned dependency.
