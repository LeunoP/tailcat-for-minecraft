// Package invite implements Tailcat for Minecraft invitation strings.
package invite

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"strings"
)

const (
	Prefix         = "mcl1_"
	CurrentVersion = 1
)

type Invitation struct {
	Version int    `json:"version"`
	Tailcat string `json:"tailcat"`
}

func New(tailcat string) Invitation {
	return Invitation{Version: CurrentVersion, Tailcat: tailcat}
}

func (i Invitation) Validate() error {
	if i.Version != CurrentVersion {
		return fmt.Errorf("unsupported invitation version %d", i.Version)
	}
	if !strings.HasPrefix(i.Tailcat, "tc") {
		return errors.New("invalid Tailcat connection token")
	}
	return nil
}

func (i Invitation) Encode() (string, error) {
	if err := i.Validate(); err != nil {
		return "", err
	}
	b, err := json.Marshal(i)
	if err != nil {
		return "", err
	}
	return Prefix + base64.RawURLEncoding.EncodeToString(b), nil
}

func Decode(s string) (Invitation, error) {
	s = strings.TrimSpace(s)
	rest, ok := strings.CutPrefix(s, Prefix)
	if !ok {
		return Invitation{}, errors.New("invitation must start with mcl1_")
	}
	b, err := base64.RawURLEncoding.DecodeString(rest)
	if err != nil {
		return Invitation{}, fmt.Errorf("decode invitation: %w", err)
	}
	var i Invitation
	d := json.NewDecoder(strings.NewReader(string(b)))
	d.DisallowUnknownFields()
	if err := d.Decode(&i); err != nil {
		return Invitation{}, fmt.Errorf("parse invitation: %w", err)
	}
	if d.Decode(new(any)) != io.EOF {
		return Invitation{}, errors.New("parse invitation: trailing data")
	}
	if err := i.Validate(); err != nil {
		return Invitation{}, err
	}
	return i, nil
}
