package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"time"

	"github.com/tailscale/tailcat"
	"tailscale.com/ipn/ipnstate"
)

const statusPort = virtualPort + 1

type statusRequest struct {
	Protocol int  `json:"protocol"`
	Health   bool `json:"health,omitempty"`
}

type tunnelStatus struct {
	Protocol  int    `json:"protocol"`
	Transport string `json:"transport"`
	Region    string `json:"region"`
	Code      string `json:"code,omitempty"`
}

func peerTransport(status *ipnstate.Status, remote net.Addr) string {
	if status == nil || remote == nil {
		return "unknown"
	}
	host, _, err := net.SplitHostPort(remote.String())
	if err != nil {
		return "unknown"
	}
	for _, peer := range status.Peer {
		if peer == nil {
			continue
		}
		for _, ip := range peer.TailscaleIPs {
			if ip.String() == host {
				if peer.CurAddr != "" {
					return "direct"
				}
				if peer.Relay != "" {
					return "relay"
				}
			}
		}
	}
	return "unknown"
}

func serveStatus(connection net.Conn, query func(bool, net.Addr) tunnelStatus) {
	defer connection.Close()
	_ = connection.SetDeadline(time.Now().Add(2 * time.Second))
	decoder := json.NewDecoder(io.LimitReader(connection, 512))
	decoder.DisallowUnknownFields()
	var request statusRequest
	if decoder.Decode(&request) != nil || request.Protocol != 2 {
		return
	}
	// This port exposes no invitation, key, identity list, or mutation operation.
	_ = json.NewEncoder(connection).Encode(query(request.Health, connection.RemoteAddr()))
}

func queryStatus(ctx context.Context, client *tailcat.Client, health bool) (tunnelStatus, error) {
	ctx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	connection, err := client.DialTCPPort(ctx, statusPort)
	if err != nil {
		return tunnelStatus{}, err
	}
	defer connection.Close()
	_ = connection.SetDeadline(time.Now().Add(time.Second))
	if err := json.NewEncoder(connection).Encode(statusRequest{Protocol: 2, Health: health}); err != nil {
		return tunnelStatus{}, err
	}
	var status tunnelStatus
	if err := json.NewDecoder(io.LimitReader(connection, 2048)).Decode(&status); err != nil {
		return tunnelStatus{}, err
	}
	if status.Protocol != 2 || (status.Transport != "unknown" && status.Transport != "direct" && status.Transport != "relay") {
		return tunnelStatus{}, io.ErrUnexpectedEOF
	}
	if len(status.Region) > 128 {
		status.Region = ""
	}
	return status, nil
}

func monitorStatus(ctx context.Context, client *tailcat.Client, out *eventWriter) {
	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			started := time.Now()
			status, err := queryStatus(ctx, client, false)
			transport := "unknown"
			local := client.Status()
			if local != nil && len(local.Peer) == 1 {
				for _, peer := range local.Peer {
					if peer == nil {
						continue
					}
					if peer.CurAddr != "" {
						transport = "direct"
					} else if peer.Relay != "" {
						transport = "relay"
					}
				}
			}
			if err != nil {
				out.detail(event{Type: "status", Transport: transport})
				continue
			}
			out.detail(event{Type: "status", Transport: transport, Region: status.Region,
				LatencyMs: fmt.Sprint(time.Since(started).Milliseconds())})
		}
	}
}
