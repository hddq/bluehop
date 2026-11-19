package mdns

import (
	"context"
	"fmt"
	"net"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"

	"github.com/grandcat/zeroconf"
	"github.com/hddq/bluehop/linux/internal/bluez"
)

const (
	ServiceType         = "_bluehop._tcp"
	ServiceDomain       = "local."
	ServiceNamePrefix   = "BlueHop"
	MaxServiceNameLen   = 50
	MaxDevicesAttrBytes = 240
)

type AdvertiseConfig struct {
	DeviceID           string
	DeviceName         string
	Port               int
	ConnectedAddresses []string
	Pairing            bool
	HostName           string
	IPs                []string
	Ifaces             []net.Interface
}

type DiscoveredPeer struct {
	ServiceName        string
	Host               string
	Port               int
	DeviceID           string
	DisplayName        string
	PairingAvailable   bool
	ConnectedAddresses []string
}

func (p DiscoveredPeer) Endpoint() string {
	return net.JoinHostPort(p.Host, strconv.Itoa(p.Port))
}

func (p DiscoveredPeer) StableKey() string {
	if strings.TrimSpace(p.DeviceID) != "" {
		return p.DeviceID
	}
	return p.ServiceName + "@" + p.Endpoint()
}

type DiscoverOptions struct {
	ExcludeDeviceID string
	PairingOnly     bool
	Ifaces          []net.Interface
}

type Advertiser interface {
	Shutdown()
}

type Discoverer interface {
	Discover(ctx context.Context, opts DiscoverOptions) ([]DiscoveredPeer, error)
}

type ZeroconfDiscoverer struct{}

func (ZeroconfDiscoverer) Discover(ctx context.Context, opts DiscoverOptions) ([]DiscoveredPeer, error) {
	return Discover(ctx, opts)
}

type zeroconfAdvertiser struct {
	mu     sync.Mutex
	server *zeroconf.Server
}

func (a *zeroconfAdvertiser) Shutdown() {
	a.mu.Lock()
	srv := a.server
	a.server = nil
	a.mu.Unlock()
	if srv != nil {
		srv.Shutdown()
	}
}

func StartAdvertising(cfg AdvertiseConfig) (Advertiser, error) {
	if cfg.Port <= 0 {
		return nil, fmt.Errorf("invalid mDNS advertise port %d", cfg.Port)
	}

	ifaces := cfg.Ifaces
	ips := cfg.IPs
	if len(ifaces) == 0 || len(ips) == 0 {
		autoIfaces, autoIPs := usableInterfacesAndIPs()
		if len(ifaces) == 0 {
			ifaces = autoIfaces
		}
		if len(ips) == 0 {
			ips = autoIPs
		}
	}
	if len(ips) == 0 {
		return nil, fmt.Errorf("no usable local IP addresses available for mDNS advertising")
	}

	hostName := strings.TrimSpace(cfg.HostName)
	if hostName == "" {
		h, err := os.Hostname()
		if err != nil || strings.TrimSpace(h) == "" {
			hostName = "bluehop-linux"
		} else {
			hostName = sanitizeHostname(h)
		}
	}

	instanceName := escapeDNSLabel(BuildServiceName(cfg.DeviceName, cfg.Pairing))
	txt := BuildTXT(cfg)

	srv, err := zeroconf.RegisterProxy(
		instanceName,
		ServiceType,
		ServiceDomain,
		cfg.Port,
		hostName,
		ips,
		txt,
		ifaces,
	)
	if err != nil {
		return nil, fmt.Errorf("register mDNS service: %w", err)
	}
	return &zeroconfAdvertiser{server: srv}, nil
}

func Discover(ctx context.Context, opts DiscoverOptions) ([]DiscoveredPeer, error) {
	ifaces := opts.Ifaces
	if len(ifaces) == 0 {
		ifaces, _ = usableInterfacesAndIPs()
	}

	var resolverOpts []zeroconf.ClientOption
	if len(ifaces) > 0 {
		resolverOpts = append(resolverOpts, zeroconf.SelectIfaces(ifaces))
	}

	resolver, err := zeroconf.NewResolver(resolverOpts...)
	if err != nil {
		// Fall back to IPv4-only if IPv6 multicast group join fails on this host.
		fallbackOpts := append([]zeroconf.ClientOption{zeroconf.SelectIPTraffic(zeroconf.IPv4)}, resolverOpts...)
		resolver, err = zeroconf.NewResolver(fallbackOpts...)
		if err != nil {
			return nil, fmt.Errorf("create mDNS resolver: %w", err)
		}
	}

	entries := make(chan *zeroconf.ServiceEntry, 32)
	if err := resolver.Browse(ctx, ServiceType, ServiceDomain, entries); err != nil {
		return nil, fmt.Errorf("browse %s: %w", ServiceType, err)
	}

	byKey := make(map[string]DiscoveredPeer)
	for entry := range entries {
		peer, ok := parseServiceEntry(entry)
		if !ok {
			continue
		}
		if opts.ExcludeDeviceID != "" && peer.DeviceID == opts.ExcludeDeviceID {
			continue
		}
		if opts.PairingOnly && !peer.PairingAvailable {
			continue
		}
		byKey[peer.StableKey()] = peer
	}

	out := make([]DiscoveredPeer, 0, len(byKey))
	for _, peer := range byKey {
		out = append(out, peer)
	}
	sort.Slice(out, func(i, j int) bool {
		li := strings.ToLower(out[i].DisplayName)
		lj := strings.ToLower(out[j].DisplayName)
		if li != lj {
			return li < lj
		}
		return out[i].StableKey() < out[j].StableKey()
	})
	return out, nil
}

func BuildServiceName(deviceName string, pairing bool) string {
	trimmed := strings.TrimSpace(deviceName)
	base := ServiceNamePrefix
	if trimmed != "" {
		base = ServiceNamePrefix + " - " + trimmed
	}
	full := base
	if pairing {
		full = base + " (pair)"
	}
	runes := []rune(full)
	if len(runes) > MaxServiceNameLen {
		full = string(runes[:MaxServiceNameLen])
	}
	return full
}

func PackDevicesAttribute(addresses []string, maxBytes int) string {
	if len(addresses) == 0 || maxBytes <= 0 {
		return ""
	}
	var b strings.Builder
	for _, addr := range addresses {
		if addr == "" {
			continue
		}
		nextLen := b.Len() + len(addr)
		if b.Len() > 0 {
			nextLen++
		}
		if nextLen > maxBytes {
			break
		}
		if b.Len() > 0 {
			b.WriteByte(',')
		}
		b.WriteString(addr)
	}
	return b.String()
}

func BuildTXT(cfg AdvertiseConfig) []string {
	addrs := normalizeAddresses(cfg.ConnectedAddresses)
	txt := []string{"v=1"}
	if id := strings.TrimSpace(cfg.DeviceID); id != "" {
		txt = append(txt, "id="+id)
	}
	if name := strings.TrimSpace(cfg.DeviceName); name != "" {
		txt = append(txt, "name="+name)
	}
	if len(addrs) > 0 {
		txt = append(txt, "device="+addrs[0])
		if packed := PackDevicesAttribute(addrs, MaxDevicesAttrBytes); packed != "" {
			txt = append(txt, "devices="+packed)
		}
	}
	if cfg.Pairing {
		txt = append(txt, "pair=1")
	}
	return txt
}

func parseServiceEntry(entry *zeroconf.ServiceEntry) (DiscoveredPeer, bool) {
	if entry == nil || entry.Port <= 0 {
		return DiscoveredPeer{}, false
	}
	host := SelectPreferredHost(entry.AddrIPv4, entry.AddrIPv6)
	if host == "" {
		return DiscoveredPeer{}, false
	}

	attrs := parseTXTRecords(entry.Text)
	if v, ok := attrs["v"]; ok && v != "" && v != "1" {
		return DiscoveredPeer{}, false
	}

	deviceID := strings.TrimSpace(attrs["id"])
	attrName := strings.TrimSpace(attrs["name"])
	pairing := attrs["pair"] == "1"

	var rawAddrs []string
	if devicesAttr := strings.TrimSpace(attrs["devices"]); devicesAttr != "" {
		rawAddrs = append(rawAddrs, strings.Split(devicesAttr, ",")...)
	}
	if singleDevice := strings.TrimSpace(attrs["device"]); singleDevice != "" {
		rawAddrs = append(rawAddrs, strings.Split(singleDevice, ",")...)
	}
	connectedAddrs := normalizeAddresses(rawAddrs)

	serviceName := unescapeDNSLabel(strings.TrimSpace(entry.Instance))
	displayName := attrName
	if displayName == "" {
		displayName = serviceName
	}
	if displayName == "" {
		displayName = host
	}

	return DiscoveredPeer{
		ServiceName:        serviceName,
		Host:               host,
		Port:               entry.Port,
		DeviceID:           deviceID,
		DisplayName:        displayName,
		PairingAvailable:   pairing,
		ConnectedAddresses: connectedAddrs,
	}, true
}

func parseTXTRecords(records []string) map[string]string {
	attrs := make(map[string]string, len(records))
	for _, rec := range records {
		k, v, ok := strings.Cut(rec, "=")
		if !ok {
			continue
		}
		key := strings.TrimSpace(k)
		if key == "" {
			continue
		}
		attrs[key] = v
	}
	return attrs
}

func normalizeAddresses(input []string) []string {
	seen := make(map[string]struct{}, len(input))
	out := make([]string, 0, len(input))
	for _, raw := range input {
		trimmed := strings.TrimSpace(raw)
		if trimmed == "" {
			continue
		}
		norm, ok := bluez.NormalizeMAC(trimmed)
		if !ok {
			norm = strings.ToUpper(trimmed)
		}
		if _, exists := seen[norm]; exists {
			continue
		}
		seen[norm] = struct{}{}
		out = append(out, norm)
	}
	return out
}

func SelectPreferredHost(ipv4s, ipv6s []net.IP) string {
	for _, ip := range ipv4s {
		if v4 := ip.To4(); v4 != nil && isUsableLocalIP(v4) {
			return v4.String()
		}
	}
	for _, ip := range ipv6s {
		if ip != nil && ip.To4() == nil && isUsableLocalIP(ip) && isUniqueLocalIPv6(ip) {
			return ip.String()
		}
	}
	for _, ip := range ipv6s {
		if ip != nil && ip.To4() == nil && isUsableLocalIP(ip) {
			return ip.String()
		}
	}
	for _, list := range [][]net.IP{ipv4s, ipv6s} {
		for _, ip := range list {
			if ip != nil && !ip.IsLoopback() && !ip.IsLinkLocalUnicast() {
				return ip.String()
			}
		}
	}
	for _, list := range [][]net.IP{ipv4s, ipv6s} {
		for _, ip := range list {
			if ip != nil && !ip.IsLoopback() {
				return ip.String()
			}
		}
	}
	for _, list := range [][]net.IP{ipv4s, ipv6s} {
		for _, ip := range list {
			if ip != nil {
				return ip.String()
			}
		}
	}
	return ""
}

func isUsableLocalIP(ip net.IP) bool {
	if ip == nil {
		return false
	}
	return !ip.IsUnspecified() && !ip.IsLoopback() && !ip.IsLinkLocalUnicast() && !ip.IsMulticast()
}

func isUniqueLocalIPv6(ip net.IP) bool {
	ip16 := ip.To16()
	return ip16 != nil && ip.To4() == nil && (ip16[0]&0xfe) == 0xfc
}

func usableInterfacesAndIPs() ([]net.Interface, []string) {
	ifaces, err := net.Interfaces()
	if err != nil {
		return nil, nil
	}
	var selected []net.Interface
	var ips []string
	seenIP := make(map[string]struct{})

	for _, iface := range ifaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 || iface.Flags&net.FlagMulticast == 0 {
			continue
		}
		addrs, err := iface.Addrs()
		if err != nil {
			continue
		}
		hasUsable := false
		for _, a := range addrs {
			ipNet, ok := a.(*net.IPNet)
			if !ok || !isUsableLocalIP(ipNet.IP) {
				continue
			}
			hasUsable = true
			ipStr := ipNet.IP.String()
			if _, exists := seenIP[ipStr]; !exists {
				seenIP[ipStr] = struct{}{}
				ips = append(ips, ipStr)
			}
		}
		if hasUsable {
			selected = append(selected, iface)
		}
	}
	return selected, ips
}

func sanitizeHostname(h string) string {
	trimmed := strings.TrimSpace(h)
	if before, ok := strings.CutSuffix(trimmed, ".local."); ok {
		trimmed = before
	} else if before, ok := strings.CutSuffix(trimmed, ".local"); ok {
		trimmed = before
	}
	trimmed = strings.ReplaceAll(trimmed, ".", "-")
	if trimmed == "" {
		return "bluehop-linux"
	}
	return trimmed
}

func escapeDNSLabel(s string) string {
	return strings.ReplaceAll(s, ".", `\.`)
}

func unescapeDNSLabel(s string) string {
	var b strings.Builder
	b.Grow(len(s))
	for i := 0; i < len(s); i++ {
		if s[i] == '\\' && i+1 < len(s) {
			i++
			b.WriteByte(s[i])
			continue
		}
		b.WriteByte(s[i])
	}
	return b.String()
}
