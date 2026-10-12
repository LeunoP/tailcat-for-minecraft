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
	senderNode := key.NewNode()
	receiverNode := key.NewNode()
	sender := discoPrivateForNode(senderNode)
	receiver := discoPrivateForNode(receiverNode)
	if sender.Public().Raw32() == senderNode.Public().Raw32() {
		t.Fatal("sender disco key is not independent from its node key")
	}
	if receiver.Public().Raw32() == receiverNode.Public().Raw32() {
		t.Fatal("receiver disco key is not independent from its node key")
	}
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

func TestUpdateEndpointsDeduplicatesAndHonorsRelayOnly(t *testing.T) {
	b := newLocoBackend(key.NewNode())
	a := netip.MustParseAddrPort("192.0.2.1:1234")
	z := netip.MustParseAddrPort("192.0.2.2:1234")
	if !b.updateEndpoints([]netip.AddrPort{z, a, a}) {
		t.Fatal("changed endpoints should be advertised")
	}
	b.mu.Lock()
	got := slices.Clone(b.eps)
	b.mu.Unlock()
	if want := []netip.AddrPort{a, z}; !slices.Equal(got, want) {
		t.Fatalf("stored endpoints = %v; want sorted unique %v", got, want)
	}
	if b.updateEndpoints([]netip.AddrPort{a, z, a}) {
		t.Fatal("same endpoint set should not trigger another advertisement")
	}
	if b.updateEndpoints(nil) {
		t.Fatal("empty endpoint set should clear state without advertising")
	}
	b.mu.Lock()
	cleared := len(b.eps) == 0
	b.mu.Unlock()
	if !cleared {
		t.Fatal("empty endpoint update did not clear the previous candidates")
	}
	b.relayOnly.Store(true)
	if b.updateEndpoints([]netip.AddrPort{a}) {
		t.Fatal("relay-only mode should suppress endpoint advertisements")
	}
}
