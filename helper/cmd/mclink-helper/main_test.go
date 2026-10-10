package main

import (
	"bytes"
	"context"
	"math/big"
	"strings"
	"testing"
)

func TestProtocolOptIn(t *testing.T) {
	var buffer bytes.Buffer
	w := newEventWriter(&buffer)
	w.detail(event{Type: "progress", Stage: "listener_ready"})
	if buffer.Len() != 0 {
		t.Fatal("legacy protocol received new event")
	}
	w.protocol = 2
	w.detail(event{Type: "progress", Stage: "listener_ready"})
	if !strings.Contains(buffer.String(), `"stage":"listener_ready"`) {
		t.Fatal(buffer.String())
	}
}

func TestInvalidInvitationProgress(t *testing.T) {
	for _, protocol := range []string{"1", "2"} {
		var buffer bytes.Buffer
		code, err := runJoin(context.Background(), newEventWriter(&buffer), []string{"--protocol", protocol, "--invite", "invalid"})
		if code != "invalid_invite" || err == nil {
			t.Fatalf("got %s, %v", code, err)
		}
		if strings.Contains(buffer.String(), "validating_invite") != (protocol == "2") {
			t.Fatal(buffer.String())
		}
	}
}

func TestParseMaxConnections(t *testing.T) {
	for _, value := range []string{"65", "999999999999999999999999999999999999999999999999"} {
		got, err := parseMaxConnections(value)
		if err != nil {
			t.Fatalf("parseMaxConnections(%q): %v", value, err)
		}
		want, _ := new(big.Int).SetString(value, 10)
		if got.Cmp(want) != 0 {
			t.Errorf("parseMaxConnections(%q) = %s, want %s", value, got, want)
		}
	}

	for _, value := range []string{"", "0", "-1", "not-a-number"} {
		if _, err := parseMaxConnections(value); err == nil {
			t.Errorf("parseMaxConnections(%q) unexpectedly succeeded", value)
		}
	}
}
