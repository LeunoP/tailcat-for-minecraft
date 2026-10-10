package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"math/big"
	"net"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"

	"github.com/tailscale/mclink/helper/internal/invite"
	"github.com/tailscale/tailcat"
	// Register router-assisted port mapping for magicsock (UPnP, NAT-PMP, and PCP).
	_ "tailscale.com/feature/condregister/portmapper"
	"tailscale.com/types/key"
	"tailscale.com/wgengine/filter"
)

const (
	virtualPort    = 25565
	maxConnections = 64
)

type event struct {
	Type           string `json:"type"`
	Mode           string `json:"mode,omitempty"`
	Invite         string `json:"invite,omitempty"`
	Address        string `json:"address,omitempty"`
	Code           string `json:"code,omitempty"`
	Message        string `json:"message,omitempty"`
	Stage          string `json:"stage,omitempty"`
	Transport      string `json:"transport,omitempty"`
	Region         string `json:"region,omitempty"`
	ConnectionID   string `json:"connectionId,omitempty"`
	BackendAddress string `json:"backendAddress,omitempty"`
	LatencyMs      string `json:"latencyMs,omitempty"`
}

type eventWriter struct {
	mu       sync.Mutex
	enc      *json.Encoder
	protocol int
}

func newEventWriter(w io.Writer) *eventWriter { return &eventWriter{enc: json.NewEncoder(w)} }
func (w *eventWriter) send(e event) error {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.enc.Encode(e)
}

func (w *eventWriter) detail(e event) {
	if w.protocol == 2 {
		_ = w.send(e)
	}
}

func main() {
	log.SetFlags(log.Ldate | log.Ltime | log.Lmicroseconds)
	out := newEventWriter(os.Stdout)
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	go func() { _, _ = io.Copy(io.Discard, os.Stdin); cancel() }()

	if len(os.Args) < 2 {
		_ = out.send(event{Type: "error", Code: "usage", Message: "expected host or join command"})
		return
	}
	var code string
	var err error
	switch os.Args[1] {
	case "host":
		code, err = runHost(ctx, out, os.Args[2:])
	case "join":
		code, err = runJoin(ctx, out, os.Args[2:])
	default:
		code, err = "usage", fmt.Errorf("unknown command %q", os.Args[1])
	}
	if err != nil && !errors.Is(err, context.Canceled) {
		_ = out.send(event{Type: "error", Code: code, Message: err.Error()})
	}
	_ = out.send(event{Type: "stopped"})
}

func runHost(ctx context.Context, out *eventWriter, args []string) (string, error) {
	fs := flag.NewFlagSet("host", flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	target := fs.String("target", "", "loopback Minecraft address")
	maxPlayersArg := fs.String("max-connections", fmt.Sprint(maxConnections), "maximum concurrent remote Minecraft connections")
	protocol := fs.Int("protocol", 1, "event protocol: 1 or 2")
	relayOnly := fs.Bool("relay-only", false, "disable UDP and use DERP relay only")
	if err := fs.Parse(args); err != nil {
		return "usage", err
	}
	if *target == "" {
		return "usage", errors.New("--target is required")
	}
	if *protocol != 1 && *protocol != 2 {
		return "usage", errors.New("unsupported protocol")
	}
	out.protocol = *protocol
	out.detail(event{Type: "progress", Stage: "relay_preparing"})
	maxPlayers, err := parseMaxConnections(*maxPlayersArg)
	if err != nil {
		return "usage", err
	}
	if err := validateLoopbackTarget(*target); err != nil {
		return "usage", err
	}

	priv := key.NewNode()
	ci := tailcat.ConnInfo{RegionID: -1}
	if err := ci.Expand(ctx, tailcat.ExpandForServer); err != nil {
		return "derp_unreachable", fmt.Errorf("select DERP region: %w", err)
	}
	if len(ci.Region) != 1 {
		return "derp_unreachable", errors.New("DERP selection returned no region")
	}
	region := ci.Region[0]
	out.detail(event{Type: "status", Region: region.RegionName, Transport: "unknown"})
	server, err := tailcat.NewServer(priv, log.Printf, region)
	if err != nil {
		return "helper_failed", fmt.Errorf("create Tailcat server: %w", err)
	}
	defer server.Close()
	server.SetRelayOnly(*relayOnly)

	publicCI := tailcat.ConnInfo{ServerPublic: tailcat.NodePublic{NodePublic: priv.Public()}, RegionID: region.RegionID}
	encoded, err := invite.New(string(publicCI.ConnBlob())).Encode()
	if err != nil {
		return "helper_failed", err
	}
	var connectionMu sync.Mutex
	activeConnections := 0
	var sequence uint64
	connections := make(map[string]string)
	reserveConnection := func() bool {
		connectionMu.Lock()
		defer connectionMu.Unlock()
		if big.NewInt(int64(activeConnections)).Cmp(maxPlayers) >= 0 {
			return false
		}
		activeConnections++
		return true
	}
	releaseConnection := func() {
		connectionMu.Lock()
		activeConnections--
		connectionMu.Unlock()
	}
	server.ServedTCPPorts = []filter.PortRange{{First: virtualPort, Last: virtualPort}}
	statusSlots := make(chan struct{}, 8)
	if out.protocol == 2 {
		server.ServedTCPPorts = append(server.ServedTCPPorts, filter.PortRange{First: statusPort, Last: statusPort})
	}
	server.OnTCP = func(port uint16) func(net.Conn) {
		if port == statusPort && out.protocol == 2 {
			return func(connection net.Conn) {
				select {
				case statusSlots <- struct{}{}:
				default:
					connection.Close()
					return
				}
				defer func() { <-statusSlots }()
				serveStatus(connection, func(health bool, remote net.Addr) tunnelStatus {
					response := tunnelStatus{Protocol: 2, Transport: peerTransport(server.Status(), remote), Region: region.RegionName}
					if health {
						connectionMu.Lock()
						full := big.NewInt(int64(activeConnections)).Cmp(maxPlayers) >= 0
						connectionMu.Unlock()
						if full {
							response.Code = "capacity"
							return response
						}
						backend, err := net.DialTimeout("tcp", *target, time.Second)
						if err != nil {
							response.Code = "backend_unreachable"
						} else {
							backend.Close()
						}
					}
					return response
				})
			}
		}
		if port != virtualPort {
			return nil
		}
		return func(tunnel net.Conn) {
			if !reserveConnection() {
				out.detail(event{Type: "warning", Code: "capacity", Message: "TCP connection resource limit reached"})
				tunnel.Close()
				return
			}
			defer releaseConnection()
			defer tunnel.Close()
			backend, err := net.DialTimeout("tcp", *target, 5*time.Second)
			if err != nil {
				out.detail(event{Type: "warning", Code: "backend_unreachable", Message: "Minecraft backend is not accepting connections"})
				log.Printf("dial Minecraft backend: %v", err)
				return
			}
			connectionMu.Lock()
			sequence++
			id := fmt.Sprint(sequence)
			connections[id] = tunnel.RemoteAddr().String()
			connectionMu.Unlock()
			out.detail(event{Type: "connection", Stage: "opened", ConnectionID: id, BackendAddress: backend.LocalAddr().String()})
			defer func() {
				connectionMu.Lock()
				delete(connections, id)
				connectionMu.Unlock()
				out.detail(event{Type: "connection", Stage: "closed", ConnectionID: id})
			}()
			tailcat.ProxyConns(tunnel, backend)
		}
	}
	if err := server.Start(); err != nil {
		return "derp_unreachable", fmt.Errorf("start Tailcat server: %w", err)
	}
	if err := out.send(event{Type: "ready", Mode: "host", Invite: encoded}); err != nil {
		return "helper_failed", err
	}
	if out.protocol == 2 {
		go func() {
			ticker := time.NewTicker(time.Second)
			defer ticker.Stop()
			for {
				select {
				case <-ctx.Done():
					return
				case <-ticker.C:
					status := server.Status()
					connectionMu.Lock()
					for id, address := range connections {
						host, _, err := net.SplitHostPort(address)
						if err != nil {
							continue
						}
						transport := "unknown"
						if status != nil {
							for _, peer := range status.Peer {
								for _, ip := range peer.TailscaleIPs {
									if ip.String() == host {
										if peer.CurAddr != "" {
											transport = "direct"
										} else if peer.Relay != "" {
											transport = "relay"
										}
									}
								}
							}
						}
						out.detail(event{Type: "status", ConnectionID: id, Transport: transport, Region: region.RegionName})
					}
					connectionMu.Unlock()
				}
			}
		}()
	}
	<-ctx.Done()
	return "", ctx.Err()
}

func parseMaxConnections(raw string) (*big.Int, error) {
	limit, ok := new(big.Int).SetString(raw, 10)
	if !ok || limit.Sign() < 1 {
		return nil, errors.New("--max-connections must be a positive integer")
	}
	return limit, nil
}

func runJoin(ctx context.Context, out *eventWriter, args []string) (string, error) {
	fs := flag.NewFlagSet("join", flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	encoded := fs.String("invite", "", "mcl1 invitation")
	protocol := fs.Int("protocol", 1, "event protocol: 1 or 2")
	relayOnly := fs.Bool("relay-only", false, "disable UDP and use DERP relay only")
	if err := fs.Parse(args); err != nil {
		return "usage", err
	}
	if *protocol != 1 && *protocol != 2 {
		return "usage", errors.New("unsupported protocol")
	}
	out.protocol = *protocol
	out.detail(event{Type: "progress", Stage: "validating_invite"})
	inv, err := invite.Decode(*encoded)
	if err != nil {
		return "invalid_invite", err
	}
	client, err := tailcat.NewClient(log.Printf, tailcat.ConnBlob(inv.Tailcat), key.NewNode())
	if err != nil {
		return "invalid_invite", fmt.Errorf("create Tailcat client: %w", err)
	}
	client.SetRelayOnly(*relayOnly)
	out.detail(event{Type: "progress", Stage: "listener_ready"})
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		client.Close()
		return "helper_failed", fmt.Errorf("listen on loopback: %w", err)
	}
	if err := out.send(event{Type: "ready", Mode: "join", Address: listener.Addr().String()}); err != nil {
		listener.Close()
		client.Close()
		return "helper_failed", err
	}

	sem := make(chan struct{}, maxConnections)
	var wg sync.WaitGroup
	go func() {
		<-ctx.Done()
		listener.Close()
		client.Close()
	}()
	defer func() {
		listener.Close()
		client.Close()
		wg.Wait()
	}()
	for {
		local, err := listener.Accept()
		if err != nil {
			if ctx.Err() != nil {
				return "", ctx.Err()
			}
			return "helper_failed", err
		}
		select {
		case sem <- struct{}{}:
		default:
			local.Close()
			continue
		}
		wg.Add(1)
		go func() {
			defer wg.Done()
			defer func() { <-sem }()
			defer local.Close()
			out.detail(event{Type: "progress", Stage: "tunnel_connecting"})
			if out.protocol == 2 {
				status, err := queryStatus(ctx, client, true)
				if err == nil {
					out.detail(event{Type: "status", Transport: status.Transport, Region: status.Region})
					if status.Code == "backend_unreachable" || status.Code == "capacity" {
						out.detail(event{Type: "warning", Code: status.Code, Message: "Host is not accepting a new Minecraft connection"})
						return
					}
				}
			}
			dialCtx, cancel := context.WithTimeout(ctx, 15*time.Second)
			tunnel, err := client.DialTCPPort(dialCtx, virtualPort)
			cancel()
			if err != nil {
				code := "remote_unreachable"
				if errors.Is(err, context.DeadlineExceeded) {
					code = "timeout"
				}
				out.detail(event{Type: "warning", Code: code, Message: "Could not connect to the remote Minecraft port"})
				log.Printf("dial remote Minecraft port: %v", err)
				return
			}
			out.detail(event{Type: "progress", Stage: "minecraft_connecting"})
			if out.protocol == 2 {
				monitorCtx, stopMonitor := context.WithCancel(ctx)
				defer stopMonitor()
				go monitorStatus(monitorCtx, client, out)
			}
			tailcat.ProxyConns(local, tunnel)
		}()
	}
}

func validateLoopbackTarget(target string) error {
	host, _, err := net.SplitHostPort(target)
	if err != nil {
		return fmt.Errorf("invalid --target: %w", err)
	}
	ip := net.ParseIP(host)
	if ip == nil || !ip.IsLoopback() {
		return errors.New("--target must be a numeric loopback address")
	}
	return nil
}
