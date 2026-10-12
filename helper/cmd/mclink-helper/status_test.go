package main

import (
	"encoding/json"
	"net"
	"net/netip"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/types/key"
	"testing"
	"time"
)

func TestPeerTransportTracksActualStatus(t *testing.T) {
	peer := &ipnstate.PeerStatus{TailscaleIPs: []netip.Addr{netip.MustParseAddr("fd7a::1")}, CurAddr: "192.0.2.1:1234"}
	status := &ipnstate.Status{Peer: map[key.NodePublic]*ipnstate.PeerStatus{key.NewNode().Public(): peer}}
	remote := &net.TCPAddr{IP: net.ParseIP("fd7a::1"), Port: 4567}
	if got := peerTransport(status, remote); got != "direct" {
		t.Fatal(got)
	}
	peer.CurAddr = ""
	peer.Relay = "tok"
	if got := peerTransport(status, remote); got != "relay" {
		t.Fatal(got)
	}
	peer.Relay = ""
	if got := peerTransport(status, remote); got != "unknown" {
		t.Fatal(got)
	}
	if got := peerTransport(status, &net.TCPAddr{IP: net.ParseIP("fd7a::2"), Port: 4567}); got != "unknown" {
		t.Fatal(got)
	}
}

func TestStatusPortDoesNotAcceptMutation(t *testing.T) {
	client, server := net.Pipe()
	defer client.Close()
	called := make(chan bool, 1)
	go serveStatus(server, func(health bool, remote net.Addr) tunnelStatus {
		called <- true
		return tunnelStatus{Protocol: 2, Transport: "unknown"}
	})
	_ = client.SetDeadline(time.Now().Add(time.Second))
	_, _ = client.Write([]byte("{\"protocol\":2,\"command\":\"stop\"}\n"))
	var response tunnelStatus
	if json.NewDecoder(client).Decode(&response) == nil {
		t.Fatal("mutation was accepted")
	}
	select {
	case <-called:
		t.Fatal("query callback was called")
	default:
	}
}

func TestStatusResponseContainsOnlyCallerRouteAndHealth(t *testing.T) {
	client, server := net.Pipe()
	defer client.Close()
	go serveStatus(server, func(health bool, remote net.Addr) tunnelStatus {
		if !health {
			t.Error("health request was lost")
		}
		return tunnelStatus{Protocol: 2, Transport: "relay", Region: "Test", Code: "backend_unreachable"}
	})
	_ = client.SetDeadline(time.Now().Add(time.Second))
	_ = json.NewEncoder(client).Encode(statusRequest{Protocol: 2, Health: true})
	var response tunnelStatus
	if err := json.NewDecoder(client).Decode(&response); err != nil {
		t.Fatal(err)
	}
	if response.Transport != "relay" || response.Code != "backend_unreachable" {
		t.Fatal(response)
	}
}
