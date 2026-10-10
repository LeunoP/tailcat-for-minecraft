// Copyright (c) Tailscale Inc & contributors
// SPDX-License-Identifier: BSD-3-Clause

package tailcat

import (
	"bytes"
	"net/netip"
	"slices"
	"testing"

	"go4.org/mem"
	"tailscale.com/disco"
	"tailscale.com/types/key"
)

func TestEncodeCallMeMaybeEncryptsEndpointCandidates(t *testing.T) {
	sender := nodePrivateAsDiscoPrivate(key.NewNode())
	receiver := key.NewDisco()
	endpoints := []netip.AddrPort{
		netip.MustParseAddrPort("192.168.1.23:41327"),
		netip.MustParseAddrPort("[2001:db8::23]:41327"),
	}

	packet := encodeCallMeMaybe(sender, receiver.Public(), endpoints)
	if !disco.LooksLikeDiscoWrapper(packet) {
		t.Fatal("packet does not have the standard encrypted disco wrapper")
	}
	if bytes.Contains(packet, []byte(endpoints[0].String())) || bytes.Contains(packet, []byte(endpoints[1].String())) {
		t.Fatal("endpoint candidates were exposed in plaintext")
	}

	source, ok := disco.Source(packet)
	if !ok {
		t.Fatal("packet has no sender disco key")
	}
	senderPublic := key.DiscoPublicFromRaw32(mem.B(source))
	if sender.Public().ShortString() != senderPublic.ShortString() {
		t.Fatal("packet sender disco key does not match the encrypting key")
	}

	boxOffset := len(disco.Magic) + key.DiscoPublicRawLen
	cleartext, ok := receiver.Shared(sender.Public()).Open(packet[boxOffset:])
	if !ok {
		t.Fatal("receiver could not decrypt endpoint discovery packet")
	}
	message, err := disco.Parse(cleartext)
	if err != nil {
		t.Fatalf("parse decrypted disco message: %v", err)
	}
	callMeMaybe, ok := message.(*disco.CallMeMaybe)
	if !ok {
		t.Fatalf("decrypted message has type %T; want *disco.CallMeMaybe", message)
	}
	if !slices.Equal(endpoints, callMeMaybe.MyNumber) {
		t.Fatalf("endpoint candidates mismatch: got %v, want %v", callMeMaybe.MyNumber, endpoints)
	}
}
