package mdns

import (
	"context"
	"net"
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/grandcat/zeroconf"
)

func TestBuildServiceName(t *testing.T) {
	if got := BuildServiceName("", false); got != "BlueHop" {
		t.Fatalf("got %q, want BlueHop", got)
	}
	if got := BuildServiceName("  Pixel 9  ", false); got != "BlueHop - Pixel 9" {
		t.Fatalf("got %q, want BlueHop - Pixel 9", got)
	}
	if got := BuildServiceName("nix-client", true); got != "BlueHop - nix-client (pair)" {
		t.Fatalf("got %q", got)
	}
	longName := strings.Repeat("a", 60)
	got := BuildServiceName(longName, true)
	if len([]rune(got)) != MaxServiceNameLen {
		t.Fatalf("len = %d, want %d (%q)", len([]rune(got)), MaxServiceNameLen, got)
	}
}

func TestPackDevicesAttributeAndBuildTXT(t *testing.T) {
	addrs := []string{
		"ac:80:0a:cd:82:a1",
		"11-22-33-44-55-66",
		"AC:80:0A:CD:82:A1",
	}
	txt := BuildTXT(AdvertiseConfig{
		DeviceID:           "linux-1",
		DeviceName:         "nix-client",
		Port:               8765,
		ConnectedAddresses: addrs,
		Pairing:            true,
	})
	want := []string{
		"v=1",
		"id=linux-1",
		"name=nix-client",
		"device=AC:80:0A:CD:82:A1",
		"devices=AC:80:0A:CD:82:A1,11:22:33:44:55:66",
		"pair=1",
	}
	if !reflect.DeepEqual(txt, want) {
		t.Fatalf("BuildTXT = %v, want %v", txt, want)
	}

	var many []string
	for i := range 20 {
		many = append(many, net.HardwareAddr{0xAA, 0xBB, 0xCC, 0xDD, 0xEE, byte(i)}.String())
	}
	packed := PackDevicesAttribute(normalizeAddresses(many), MaxDevicesAttrBytes)
	if len(packed) > MaxDevicesAttrBytes || len(packed) == 0 {
		t.Fatalf("packed length = %d, want 1..%d", len(packed), MaxDevicesAttrBytes)
	}
}

func TestSelectPreferredHostAndParseServiceEntry(t *testing.T) {
	v4LinkLocal := net.ParseIP("169.254.1.10")
	v4LAN := net.ParseIP("192.168.10.15")
	v6LinkLocal := net.ParseIP("fe80::1")
	v6ULA := net.ParseIP("fd12:3456:789a::1")
	v6Global := net.ParseIP("2001:db8::1")

	if got := SelectPreferredHost([]net.IP{v4LinkLocal, v4LAN}, []net.IP{v6ULA}); got != "192.168.10.15" {
		t.Fatalf("got %q, want 192.168.10.15", got)
	}
	if got := SelectPreferredHost([]net.IP{v4LinkLocal}, []net.IP{v6LinkLocal, v6Global, v6ULA}); got != "fd12:3456:789a::1" {
		t.Fatalf("got %q, want fd12:3456:789a::1", got)
	}
	if got := SelectPreferredHost(nil, []net.IP{v6LinkLocal, v6Global}); got != "2001:db8::1" {
		t.Fatalf("got %q, want 2001:db8::1", got)
	}

	entry := &zeroconf.ServiceEntry{
		ServiceRecord: *zeroconf.NewServiceRecord(`BlueHop\ -\ motorola\.edge`, ServiceType, ServiceDomain),
		Port:          8765,
		AddrIPv4:      []net.IP{v4LAN},
		AddrIPv6:      []net.IP{v6ULA},
		Text: []string{
			"v=1",
			"id=phone-1",
			"name=motorola edge",
			"device=ac:80:0a:cd:82:a1",
			"devices=AC:80:0A:CD:82:A1,11:22:33:44:55:66",
			"pair=1",
		},
	}
	peer, ok := parseServiceEntry(entry)
	if !ok {
		t.Fatalf("expected parseServiceEntry to succeed")
	}
	if peer.ServiceName != "BlueHop - motorola.edge" || peer.DisplayName != "motorola edge" {
		t.Fatalf("unexpected names: %+v", peer)
	}
	if !peer.PairingAvailable || peer.Endpoint() != "192.168.10.15:8765" {
		t.Fatalf("unexpected peer: %+v", peer)
	}
	if !reflect.DeepEqual(peer.ConnectedAddresses, []string{"AC:80:0A:CD:82:A1", "11:22:33:44:55:66"}) {
		t.Fatalf("unexpected connected addresses: %v", peer.ConnectedAddresses)
	}

	entry.Text = []string{"v=2", "id=phone-1"}
	if _, ok := parseServiceEntry(entry); ok {
		t.Fatalf("expected v=2 entry to be ignored")
	}
}

func TestDNSLabelEscaping(t *testing.T) {
	orig := "BlueHop - host.lan (pair)"
	escaped := escapeDNSLabel(orig)
	if escaped != `BlueHop - host\.lan (pair)` {
		t.Fatalf("escaped = %q", escaped)
	}
	if unescaped := unescapeDNSLabel(`BlueHop\ -\ host\.lan\ (pair)`); unescaped != orig {
		t.Fatalf("unescaped = %q, want %q", unescaped, orig)
	}
}

func TestAdvertiseAndDiscoverLocal(t *testing.T) {
	ifaces, ips := usableInterfacesAndIPs()
	if len(ifaces) == 0 || len(ips) == 0 {
		t.Skip("no usable non-loopback multicast interface in test environment")
	}

	adv, err := StartAdvertising(AdvertiseConfig{
		DeviceID:           "test-local-mdns-id",
		DeviceName:         "test-linux",
		Port:               18765,
		ConnectedAddresses: []string{"AC:80:0A:CD:82:A1"},
		Pairing:            false,
		Ifaces:             ifaces,
		IPs:                ips,
	})
	if err != nil {
		t.Skipf("mDNS socket unavailable in this environment: %v", err)
	}
	defer adv.Shutdown()

	ctx, cancel := context.WithTimeout(context.Background(), 1500*time.Millisecond)
	defer cancel()

	peers, err := Discover(ctx, DiscoverOptions{Ifaces: ifaces})
	if err != nil {
		t.Fatalf("Discover() error = %v", err)
	}
	found := false
	for _, p := range peers {
		if p.DeviceID == "test-local-mdns-id" && p.Port == 18765 {
			found = true
			if p.DisplayName != "test-linux" || len(p.ConnectedAddresses) != 1 || p.ConnectedAddresses[0] != "AC:80:0A:CD:82:A1" {
				t.Fatalf("unexpected discovered peer fields: %+v", p)
			}
		}
	}
	if !found {
		t.Logf("multicast loopback/self-discovery not returned on this host (peers=%+v)", peers)
	}
}
