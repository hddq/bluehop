package bluez

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/godbus/dbus/v5"
)

type fakeSignalBackend struct {
	fakeBackend
	sigCh   chan *dbus.Signal
	subErr  error
	closedC chan struct{}
}

func (f *fakeSignalBackend) SubscribeSignals(ctx context.Context) (<-chan *dbus.Signal, error) {
	if f.subErr != nil {
		return nil, f.subErr
	}
	out := make(chan *dbus.Signal, 16)
	go func() {
		defer close(out)
		if f.closedC != nil {
			defer close(f.closedC)
		}
		for {
			select {
			case <-ctx.Done():
				return
			case sig, ok := <-f.sigCh:
				if !ok {
					return
				}
				out <- sig
			}
		}
	}()
	return out, nil
}

func TestIsRelevantSignal(t *testing.T) {
	tests := []struct {
		name string
		sig  *dbus.Signal
		want bool
	}{
		{
			name: "device Connected change is relevant",
			sig: &dbus.Signal{
				Name: signalPropertiesChange,
				Body: []any{
					deviceInterface,
					map[string]dbus.Variant{"Connected": dbus.MakeVariant(true)},
					[]string{},
				},
			},
			want: true,
		},
		{
			name: "device RSSI noise is ignored",
			sig: &dbus.Signal{
				Name: signalPropertiesChange,
				Body: []any{
					deviceInterface,
					map[string]dbus.Variant{"RSSI": dbus.MakeVariant(int16(-62))},
					[]string{},
				},
			},
			want: false,
		},
		{
			name: "non-device PropertiesChanged is ignored",
			sig: &dbus.Signal{
				Name: signalPropertiesChange,
				Body: []any{
					adapterInterface,
					map[string]dbus.Variant{"Discovering": dbus.MakeVariant(true)},
					[]string{},
				},
			},
			want: false,
		},
		{
			name: "invalidated Connected property is relevant",
			sig: &dbus.Signal{
				Name: signalPropertiesChange,
				Body: []any{
					deviceInterface,
					map[string]dbus.Variant{},
					[]string{"Connected"},
				},
			},
			want: true,
		},
		{
			name: "InterfacesAdded with Device1 is relevant",
			sig: &dbus.Signal{
				Name: signalInterfacesAdded,
				Body: []any{
					dbus.ObjectPath("/org/bluez/hci0/dev_AA_BB_CC_DD_EE_01"),
					map[string]map[string]dbus.Variant{
						deviceInterface: {},
					},
				},
			},
			want: true,
		},
		{
			name: "InterfacesRemoved with Device1 is relevant",
			sig: &dbus.Signal{
				Name: signalInterfacesRemove,
				Body: []any{
					dbus.ObjectPath("/org/bluez/hci0/dev_AA_BB_CC_DD_EE_01"),
					[]string{deviceInterface},
				},
			},
			want: true,
		},
		{
			name: "InterfacesRemoved unrelated interface is ignored",
			sig: &dbus.Signal{
				Name: signalInterfacesRemove,
				Body: []any{
					dbus.ObjectPath("/org/bluez/hci0/dev_AA_BB_CC_DD_EE_01/fd0"),
					[]string{"org.bluez.MediaTransport1"},
				},
			},
			want: false,
		},
		{
			name: "NameOwnerChanged for org.bluez is relevant",
			sig: &dbus.Signal{
				Name: signalNameOwnerChanged,
				Body: []any{serviceName, ":1.5", ":1.9"},
			},
			want: true,
		},
		{
			name: "NameOwnerChanged for another service is ignored",
			sig: &dbus.Signal{
				Name: signalNameOwnerChanged,
				Body: []any{"org.freedesktop.NetworkManager", ":1.2", ":1.3"},
			},
			want: false,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			if got := isRelevantSignal(tc.sig); got != tc.want {
				t.Fatalf("isRelevantSignal() = %v, want %v", got, tc.want)
			}
		})
	}
}

func TestWatchChangesFiltersAndCloses(t *testing.T) {
	t.Run("unsupported backend returns ErrWatchUnsupported", func(t *testing.T) {
		c := NewClient(&fakeBackend{})
		if _, err := c.WatchChanges(context.Background()); !errors.Is(err, ErrWatchUnsupported) {
			t.Fatalf("WatchChanges() err = %v, want ErrWatchUnsupported", err)
		}
	})

	t.Run("emits on relevant signal and closes on cancel", func(t *testing.T) {
		sb := &fakeSignalBackend{
			sigCh:   make(chan *dbus.Signal, 8),
			closedC: make(chan struct{}),
		}
		c := NewClient(sb)

		ctx, cancel := context.WithCancel(context.Background())
		changes, err := c.WatchChanges(ctx)
		if err != nil {
			t.Fatalf("WatchChanges() error = %v", err)
		}

		// Send irrelevant RSSI signal first; should not notify.
		sb.sigCh <- &dbus.Signal{
			Name: signalPropertiesChange,
			Body: []any{
				deviceInterface,
				map[string]dbus.Variant{"RSSI": dbus.MakeVariant(int16(-50))},
				[]string{},
			},
		}
		select {
		case <-changes:
			t.Fatalf("unexpected notification for RSSI signal")
		case <-time.After(30 * time.Millisecond):
		}

		// Send Connected change; should notify.
		sb.sigCh <- &dbus.Signal{
			Name: signalPropertiesChange,
			Body: []any{
				deviceInterface,
				map[string]dbus.Variant{"Connected": dbus.MakeVariant(true)},
				[]string{},
			},
		}
		select {
		case <-changes:
		case <-time.After(time.Second):
			t.Fatalf("timed out waiting for change notification")
		}

		cancel()
		select {
		case _, ok := <-changes:
			if ok {
				t.Fatalf("expected changes channel to close on cancel")
			}
		case <-time.After(time.Second):
			t.Fatalf("timed out waiting for changes channel to close")
		}
		<-sb.closedC
	})
}
