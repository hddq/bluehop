package bluez

import (
	"context"
	"errors"
	"fmt"
	"sort"
	"strings"

	"github.com/godbus/dbus/v5"
)

const (
	serviceName            = "org.bluez"
	rootObjectPath         = dbus.ObjectPath("/")
	objectManagerInterface = "org.freedesktop.DBus.ObjectManager"
	adapterInterface       = "org.bluez.Adapter1"
	deviceInterface        = "org.bluez.Device1"

	dbusErrAlreadyConnected = "org.bluez.Error.AlreadyConnected"
	dbusErrNotConnected     = "org.bluez.Error.NotConnected"
	dbusErrNotReady         = "org.bluez.Error.NotReady"
	dbusErrServiceUnknown   = "org.freedesktop.DBus.Error.ServiceUnknown"
	dbusErrNameHasNoOwner   = "org.freedesktop.DBus.Error.NameHasNoOwner"
)

var (
	ErrBlueZUnavailable  = errors.New("BlueZ D-Bus service (org.bluez) is not available")
	ErrNoAdapter         = errors.New("no Bluetooth adapter found")
	ErrAdapterPoweredOff = errors.New("Bluetooth adapter is powered off")
	ErrDeviceNotFound    = errors.New("paired Bluetooth device not found")
	ErrAmbiguousDevice   = errors.New("ambiguous Bluetooth device match")
	ErrAlreadyConnected  = errors.New("Bluetooth device is already connected")
	ErrNotConnected      = errors.New("Bluetooth device is not connected")
)

type ManagedObjects map[dbus.ObjectPath]map[string]map[string]dbus.Variant

type Backend interface {
	GetManagedObjects(ctx context.Context) (ManagedObjects, error)
	CallDeviceMethod(ctx context.Context, path dbus.ObjectPath, method string) error
	Close() error
}

type Controller interface {
	ListDevices(ctx context.Context) ([]Device, error)
	FindDevice(ctx context.Context, query string) (Device, error)
	Connect(ctx context.Context, query string) (Device, error)
	Disconnect(ctx context.Context, query string) (Device, error)
	Close() error
}

type Device struct {
	Path        string
	AdapterPath string
	Address     string
	Name        string
	Alias       string
	Icon        string
	Paired      bool
	Bonded      bool
	Trusted     bool
	Connected   bool
}

func (d Device) DisplayName() string {
	if s := strings.TrimSpace(d.Alias); s != "" {
		return s
	}
	if s := strings.TrimSpace(d.Name); s != "" {
		return s
	}
	return d.Address
}

func (d Device) IsPaired() bool {
	return d.Paired || d.Bonded
}

type Client struct {
	backend Backend
}

func ConnectSystem() (*Client, error) {
	conn, err := dbus.ConnectSystemBus()
	if err != nil {
		return nil, fmt.Errorf("connect system D-Bus: %w", err)
	}
	return &Client{backend: &systemBackend{conn: conn}}, nil
}

func NewClient(backend Backend) *Client {
	return &Client{backend: backend}
}

func (c *Client) Close() error {
	if c == nil || c.backend == nil {
		return nil
	}
	return c.backend.Close()
}

func (c *Client) ListDevices(ctx context.Context) ([]Device, error) {
	objects, err := c.backend.GetManagedObjects(ctx)
	if err != nil {
		return nil, translateDBusError(err)
	}

	adapterCount := 0
	var devices []Device
	for path, ifaces := range objects {
		if _, ok := ifaces[adapterInterface]; ok {
			adapterCount++
		}
		props, ok := ifaces[deviceInterface]
		if !ok {
			continue
		}
		dev, ok := parseDevice(path, props)
		if !ok || !dev.IsPaired() {
			continue
		}
		devices = append(devices, dev)
	}

	if adapterCount == 0 && len(devices) == 0 {
		return nil, ErrNoAdapter
	}

	sort.Slice(devices, func(i, j int) bool {
		li := strings.ToLower(devices[i].DisplayName())
		lj := strings.ToLower(devices[j].DisplayName())
		if li != lj {
			return li < lj
		}
		if devices[i].Address != devices[j].Address {
			return devices[i].Address < devices[j].Address
		}
		return devices[i].Path < devices[j].Path
	})
	return devices, nil
}

func (c *Client) FindDevice(ctx context.Context, query string) (Device, error) {
	trimmed := strings.TrimSpace(query)
	if trimmed == "" {
		return Device{}, fmt.Errorf("%w: empty device query", ErrDeviceNotFound)
	}

	devices, err := c.ListDevices(ctx)
	if err != nil {
		return Device{}, err
	}

	for _, dev := range devices {
		if dev.Path == trimmed {
			return dev, nil
		}
	}

	if mac, ok := NormalizeMAC(trimmed); ok {
		var matches []Device
		for _, dev := range devices {
			if dev.Address == mac {
				matches = append(matches, dev)
			}
		}
		return pickSingleMatch(trimmed, matches)
	}

	var exact []Device
	for _, dev := range devices {
		if strings.EqualFold(strings.TrimSpace(dev.Alias), trimmed) ||
			strings.EqualFold(strings.TrimSpace(dev.Name), trimmed) {
			exact = append(exact, dev)
		}
	}
	if len(exact) > 0 {
		return pickSingleMatch(trimmed, exact)
	}

	lower := strings.ToLower(trimmed)
	var partial []Device
	for _, dev := range devices {
		if strings.Contains(strings.ToLower(dev.Alias), lower) ||
			strings.Contains(strings.ToLower(dev.Name), lower) {
			partial = append(partial, dev)
		}
	}
	return pickSingleMatch(trimmed, partial)
}

func (c *Client) Connect(ctx context.Context, query string) (Device, error) {
	dev, err := c.FindDevice(ctx, query)
	if err != nil {
		return Device{}, err
	}
	if err := c.backend.CallDeviceMethod(ctx, dbus.ObjectPath(dev.Path), "Connect"); err != nil {
		if de, ok := extractDBusError(err); ok {
			switch de.Name {
			case dbusErrAlreadyConnected:
				return dev, fmt.Errorf("%w: %s (%s)", ErrAlreadyConnected, dev.DisplayName(), dev.Address)
			case dbusErrNotReady:
				return Device{}, fmt.Errorf("%w: %s", ErrAdapterPoweredOff, dbusErrorDetail(de))
			}
		}
		return Device{}, fmt.Errorf("connect %s (%s): %w", dev.DisplayName(), dev.Address, translateDBusError(err))
	}
	dev.Connected = true
	return dev, nil
}

func (c *Client) Disconnect(ctx context.Context, query string) (Device, error) {
	dev, err := c.FindDevice(ctx, query)
	if err != nil {
		return Device{}, err
	}
	if err := c.backend.CallDeviceMethod(ctx, dbus.ObjectPath(dev.Path), "Disconnect"); err != nil {
		if de, ok := extractDBusError(err); ok {
			switch de.Name {
			case dbusErrNotConnected:
				return dev, fmt.Errorf("%w: %s (%s)", ErrNotConnected, dev.DisplayName(), dev.Address)
			case dbusErrNotReady:
				return Device{}, fmt.Errorf("%w: %s", ErrAdapterPoweredOff, dbusErrorDetail(de))
			}
		}
		return Device{}, fmt.Errorf("disconnect %s (%s): %w", dev.DisplayName(), dev.Address, translateDBusError(err))
	}
	dev.Connected = false
	return dev, nil
}

func pickSingleMatch(query string, matches []Device) (Device, error) {
	switch len(matches) {
	case 0:
		return Device{}, fmt.Errorf("%w: %q", ErrDeviceNotFound, query)
	case 1:
		return matches[0], nil
	default:
		labels := make([]string, 0, len(matches))
		for _, m := range matches {
			labels = append(labels, fmt.Sprintf("%s (%s)", m.DisplayName(), m.Address))
		}
		return Device{}, fmt.Errorf("%w %q: %s", ErrAmbiguousDevice, query, strings.Join(labels, ", "))
	}
}

func NormalizeMAC(input string) (string, bool) {
	s := strings.TrimSpace(input)
	if len(s) != 17 {
		return "", false
	}
	sep := s[2]
	if sep != ':' && sep != '-' {
		return "", false
	}
	var out [17]byte
	for i := 0; i < 17; i++ {
		if i%3 == 2 {
			if s[i] != sep {
				return "", false
			}
			out[i] = ':'
			continue
		}
		c := s[i]
		switch {
		case c >= '0' && c <= '9':
			out[i] = c
		case c >= 'a' && c <= 'f':
			out[i] = c - ('a' - 'A')
		case c >= 'A' && c <= 'F':
			out[i] = c
		default:
			return "", false
		}
	}
	return string(out[:]), true
}

func parseDevice(path dbus.ObjectPath, props map[string]dbus.Variant) (Device, bool) {
	rawAddr, _ := propString(props, "Address")
	addr, ok := NormalizeMAC(rawAddr)
	if !ok {
		addr = strings.ToUpper(strings.TrimSpace(rawAddr))
	}
	if addr == "" {
		return Device{}, false
	}
	adapterPath, _ := propObjectPath(props, "Adapter")
	name, _ := propString(props, "Name")
	alias, _ := propString(props, "Alias")
	icon, _ := propString(props, "Icon")
	paired, _ := propBool(props, "Paired")
	bonded, _ := propBool(props, "Bonded")
	trusted, _ := propBool(props, "Trusted")
	connected, _ := propBool(props, "Connected")

	return Device{
		Path:        string(path),
		AdapterPath: string(adapterPath),
		Address:     addr,
		Name:        name,
		Alias:       alias,
		Icon:        icon,
		Paired:      paired,
		Bonded:      bonded,
		Trusted:     trusted,
		Connected:   connected,
	}, true
}

func propString(props map[string]dbus.Variant, key string) (string, bool) {
	v, ok := props[key]
	if !ok || v.Value() == nil {
		return "", false
	}
	var s string
	if err := v.Store(&s); err != nil {
		return "", false
	}
	return s, true
}

func propBool(props map[string]dbus.Variant, key string) (bool, bool) {
	v, ok := props[key]
	if !ok || v.Value() == nil {
		return false, false
	}
	var b bool
	if err := v.Store(&b); err != nil {
		return false, false
	}
	return b, true
}

func propObjectPath(props map[string]dbus.Variant, key string) (dbus.ObjectPath, bool) {
	v, ok := props[key]
	if !ok || v.Value() == nil {
		return "", false
	}
	var p dbus.ObjectPath
	if err := v.Store(&p); err != nil {
		return "", false
	}
	return p, true
}

func extractDBusError(err error) (dbus.Error, bool) {
	var de dbus.Error
	if errors.As(err, &de) {
		return de, true
	}
	var dep *dbus.Error
	if errors.As(err, &dep) && dep != nil {
		return *dep, true
	}
	return dbus.Error{}, false
}

func dbusErrorDetail(de dbus.Error) string {
	if len(de.Body) > 0 {
		if msg, ok := de.Body[0].(string); ok && strings.TrimSpace(msg) != "" {
			return fmt.Sprintf("%s (%s)", strings.TrimSpace(msg), de.Name)
		}
	}
	return de.Name
}

func translateDBusError(err error) error {
	if err == nil {
		return nil
	}
	if de, ok := extractDBusError(err); ok {
		switch de.Name {
		case dbusErrServiceUnknown, dbusErrNameHasNoOwner:
			return fmt.Errorf("%w: %s", ErrBlueZUnavailable, dbusErrorDetail(de))
		default:
			return fmt.Errorf("%s", dbusErrorDetail(de))
		}
	}
	return err
}

type systemBackend struct {
	conn *dbus.Conn
}

func (b *systemBackend) GetManagedObjects(ctx context.Context) (ManagedObjects, error) {
	var objects ManagedObjects
	obj := b.conn.Object(serviceName, rootObjectPath)
	call := obj.CallWithContext(ctx, objectManagerInterface+".GetManagedObjects", 0)
	if err := call.Store(&objects); err != nil {
		return nil, err
	}
	return objects, nil
}

func (b *systemBackend) CallDeviceMethod(ctx context.Context, path dbus.ObjectPath, method string) error {
	obj := b.conn.Object(serviceName, path)
	return obj.CallWithContext(ctx, deviceInterface+"."+method, 0).Err
}

func (b *systemBackend) Close() error {
	return b.conn.Close()
}
