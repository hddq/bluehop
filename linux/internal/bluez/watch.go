package bluez

import (
	"context"
	"errors"
	"fmt"

	"github.com/godbus/dbus/v5"
)

const (
	dbusDaemonService      = "org.freedesktop.DBus"
	propertiesInterface    = "org.freedesktop.DBus.Properties"
	signalPropertiesChange = propertiesInterface + ".PropertiesChanged"
	signalInterfacesAdded  = objectManagerInterface + ".InterfacesAdded"
	signalInterfacesRemove = objectManagerInterface + ".InterfacesRemoved"
	signalNameOwnerChanged = dbusDaemonService + ".NameOwnerChanged"
)

// ErrWatchUnsupported is returned when a Controller backend does not support D-Bus signal watching.
var ErrWatchUnsupported = errors.New("BlueZ change watching is not supported by this backend")

// ChangeWatcher notifies callers whenever BlueZ device connection or pairing state may have changed.
type ChangeWatcher interface {
	WatchChanges(ctx context.Context) (<-chan struct{}, error)
}

// SignalBackend is optionally implemented by Backend instances that can stream BlueZ D-Bus signals.
type SignalBackend interface {
	SubscribeSignals(ctx context.Context) (<-chan *dbus.Signal, error)
}

// WatchChanges subscribes to relevant BlueZ D-Bus signals and emits a coalesced notification
// whenever device connection/pairing state or BlueZ ownership changes.
func (c *Client) WatchChanges(ctx context.Context) (<-chan struct{}, error) {
	if c == nil || c.backend == nil {
		return nil, ErrWatchUnsupported
	}
	sb, ok := c.backend.(SignalBackend)
	if !ok {
		return nil, ErrWatchUnsupported
	}

	rawCh, err := sb.SubscribeSignals(ctx)
	if err != nil {
		return nil, translateDBusError(err)
	}

	out := make(chan struct{}, 1)
	go func() {
		defer close(out)
		for {
			select {
			case <-ctx.Done():
				return
			case sig, ok := <-rawCh:
				if !ok {
					return
				}
				if !isRelevantSignal(sig) {
					continue
				}
				select {
				case out <- struct{}{}:
				default:
				}
			}
		}
	}()
	return out, nil
}

func isRelevantSignal(sig *dbus.Signal) bool {
	if sig == nil {
		return false
	}
	switch sig.Name {
	case signalPropertiesChange:
		if len(sig.Body) < 2 {
			return false
		}
		iface, ok := sig.Body[0].(string)
		if !ok || iface != deviceInterface {
			return false
		}
		if changed, ok := sig.Body[1].(map[string]dbus.Variant); ok {
			for key := range changed {
				if isRelevantDeviceProperty(key) {
					return true
				}
			}
		}
		if len(sig.Body) >= 3 {
			if invalidated, ok := sig.Body[2].([]string); ok {
				for _, key := range invalidated {
					if isRelevantDeviceProperty(key) {
						return true
					}
				}
			}
		}
		return false

	case signalInterfacesAdded:
		if len(sig.Body) < 2 {
			return true
		}
		if ifaces, ok := sig.Body[1].(map[string]map[string]dbus.Variant); ok {
			_, hasDevice := ifaces[deviceInterface]
			_, hasAdapter := ifaces[adapterInterface]
			return hasDevice || hasAdapter
		}
		return true

	case signalInterfacesRemove:
		if len(sig.Body) < 2 {
			return true
		}
		if ifaces, ok := sig.Body[1].([]string); ok {
			for _, iface := range ifaces {
				if iface == deviceInterface || iface == adapterInterface {
					return true
				}
			}
			return false
		}
		return true

	case signalNameOwnerChanged:
		if len(sig.Body) < 1 {
			return false
		}
		name, ok := sig.Body[0].(string)
		return ok && name == serviceName

	default:
		return false
	}
}

func isRelevantDeviceProperty(key string) bool {
	switch key {
	case "Connected", "Paired", "Bonded", "Address", "Alias", "Name":
		return true
	default:
		return false
	}
}

var bluezSignalRules = [][]dbus.MatchOption{
	{
		dbus.WithMatchSender(serviceName),
		dbus.WithMatchInterface(propertiesInterface),
		dbus.WithMatchMember("PropertiesChanged"),
		dbus.WithMatchArg(0, deviceInterface),
	},
	{
		dbus.WithMatchSender(serviceName),
		dbus.WithMatchInterface(objectManagerInterface),
		dbus.WithMatchMember("InterfacesAdded"),
	},
	{
		dbus.WithMatchSender(serviceName),
		dbus.WithMatchInterface(objectManagerInterface),
		dbus.WithMatchMember("InterfacesRemoved"),
	},
	{
		dbus.WithMatchSender(dbusDaemonService),
		dbus.WithMatchInterface(dbusDaemonService),
		dbus.WithMatchMember("NameOwnerChanged"),
		dbus.WithMatchArg(0, serviceName),
	},
}

func (b *systemBackend) SubscribeSignals(ctx context.Context) (<-chan *dbus.Signal, error) {
	added := make([][]dbus.MatchOption, 0, len(bluezSignalRules))
	for _, rule := range bluezSignalRules {
		if err := b.conn.AddMatchSignalContext(ctx, rule...); err != nil {
			for _, prev := range added {
				_ = b.conn.RemoveMatchSignal(prev...)
			}
			return nil, fmt.Errorf("subscribe BlueZ D-Bus signals: %w", err)
		}
		added = append(added, rule)
	}

	rawCh := make(chan *dbus.Signal, 32)
	b.conn.Signal(rawCh)

	out := make(chan *dbus.Signal, 32)
	go func() {
		defer func() {
			b.conn.RemoveSignal(rawCh)
			for _, rule := range added {
				_ = b.conn.RemoveMatchSignal(rule...)
			}
			close(out)
		}()
		for {
			select {
			case <-ctx.Done():
				return
			case sig, ok := <-rawCh:
				if !ok {
					return
				}
				select {
				case out <- sig:
				case <-ctx.Done():
					return
				}
			}
		}
	}()
	return out, nil
}
