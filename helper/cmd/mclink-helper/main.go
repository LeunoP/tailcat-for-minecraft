package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"

	"github.com/tailscale/mclink/helper/internal/invite"
	"github.com/tailscale/tailcat"
	"tailscale.com/types/key"
	"tailscale.com/wgengine/filter"
)

const (
	virtualPort    = 25565
	maxConnections = 64
)

type event struct {
	Type    string `json:"type"`
	Mode    string `json:"mode,omitempty"`
	Invite  string `json:"invite,omitempty"`
	Address string `json:"address,omitempty"`
	Code    string `json:"code,omitempty"`
	Message string `json:"message,omitempty"`
}

type eventWriter struct {
	mu  sync.Mutex
	enc *json.Encoder
}

func newEventWriter(w io.Writer) *eventWriter { return &eventWriter{enc: json.NewEncoder(w)} }
func (w *eventWriter) send(e event) error {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.enc.Encode(e)
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
	if err := fs.Parse(args); err != nil {
		return "usage", err
	}
	if *target == "" {
		return "usage", errors.New("--target is required")
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
	server, err := tailcat.NewServer(priv, log.Printf, region)
	if err != nil {
		return "helper_failed", fmt.Errorf("create Tailcat server: %w", err)
	}
	defer server.Close()

	publicCI := tailcat.ConnInfo{ServerPublic: tailcat.NodePublic{NodePublic: priv.Public()}, RegionID: region.RegionID}
	encoded, err := invite.New(string(publicCI.ConnBlob())).Encode()
	if err != nil {
		return "helper_failed", err
	}
	sem := make(chan struct{}, maxConnections)
	server.ServedTCPPorts = []filter.PortRange{{First: virtualPort, Last: virtualPort}}
	server.OnTCP = func(port uint16) func(net.Conn) {
		if port != virtualPort {
			return nil
		}
		return func(tunnel net.Conn) {
			select {
			case sem <- struct{}{}:
				defer func() { <-sem }()
			default:
				tunnel.Close()
				return
			}
			defer tunnel.Close()
			backend, err := net.DialTimeout("tcp", *target, 5*time.Second)
			if err != nil {
				log.Printf("dial Minecraft backend: %v", err)
				return
			}
			tailcat.ProxyConns(tunnel, backend)
		}
	}
	if err := server.Start(); err != nil {
		return "derp_unreachable", fmt.Errorf("start Tailcat server: %w", err)
	}
	if err := out.send(event{Type: "ready", Mode: "host", Invite: encoded}); err != nil {
		return "helper_failed", err
	}
	<-ctx.Done()
	return "", ctx.Err()
}

func runJoin(ctx context.Context, out *eventWriter, args []string) (string, error) {
	fs := flag.NewFlagSet("join", flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	encoded := fs.String("invite", "", "mcl1 invitation")
	if err := fs.Parse(args); err != nil {
		return "usage", err
	}
	inv, err := invite.Decode(*encoded)
	if err != nil {
		return "invalid_invite", err
	}
	client, err := tailcat.NewClient(log.Printf, tailcat.ConnBlob(inv.Tailcat), key.NewNode())
	if err != nil {
		return "invalid_invite", fmt.Errorf("create Tailcat client: %w", err)
	}
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
			dialCtx, cancel := context.WithTimeout(ctx, 15*time.Second)
			tunnel, err := client.DialTCPPort(dialCtx, virtualPort)
			cancel()
			if err != nil {
				log.Printf("dial remote Minecraft port: %v", err)
				return
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
