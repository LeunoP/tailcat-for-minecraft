package invite

import (
	"encoding/base64"
	"testing"
)

func TestRoundTrip(t *testing.T) {
	original := Invitation{Version: 1, Tailcat: "tc_example"}
	s, err := original.Encode()
	if err != nil {
		t.Fatal(err)
	}
	got, err := Decode(s)
	if err != nil {
		t.Fatal(err)
	}
	if got != original {
		t.Fatalf("round trip mismatch: %#v", got)
	}
}

func TestMalformed(t *testing.T) {
	cases := []string{
		"",
		"tc_abc",
		"mcl1_!",
		"mcl1_" + base64.RawURLEncoding.EncodeToString([]byte(`{"version":2,"tailcat":"tc_x"}`)),
		"mcl1_" + base64.RawURLEncoding.EncodeToString([]byte(`{"version":1,"tailcat":"tc_x","secret":"obsolete"}`)),
	}
	for _, s := range cases {
		if _, err := Decode(s); err == nil {
			t.Errorf("Decode(%q) unexpectedly succeeded", s)
		}
	}
}

func TestNew(t *testing.T) {
	got := New("tc_a")
	if got.Version != CurrentVersion || got.Tailcat != "tc_a" {
		t.Fatalf("New returned %#v", got)
	}
}
