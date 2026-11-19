package bluez

import (
	"context"
	"errors"
	"strings"
	"testing"

	"github.com/godbus/dbus/v5"
)

type fakeBackend struct {
	objects     ManagedObjects
	objectsErr  error
	methodErr   error
	calledPath  dbus.ObjectPath
	calledOp    string
	closeCalled bool
}

func (f *fakeBackend) GetManagedObjects(context.Context) (ManagedObjects, error) {
	if f.objectsErr != nil {
		return nil, f.objectsErr
	}
	return f.objects, nil
}

func (f *fakeBackend) CallDeviceMethod(_ context.Context, path dbus.ObjectPath, method string) error {
	f.calledPath = path
	f.calledOp = method
	return f.methodErr
}

func (f *fakeBackend) Close() error {
	f.closeCalled = true
	return nil
}

func sampleObjects() ManagedObjects {
	return ManagedObjects{
		"/org/bluez/hci0": {
			adapterInterface: {
				"Powered": dbus.MakeVariant(true),
				"Address": dbus.MakeVariant("00:11:22:33:44:55"),
			},
		},
		"/org/bluez/hci0/dev_AA_BB_CC_DD_EE_01": {
			deviceInterface: {
				"Adapter":   dbus.MakeVariant(dbus.ObjectPath("/org/bluez/hci0")),
				"Address":   dbus.MakeVariant("aa:bb:cc:dd:ee:01"),
				"Name":      dbus.MakeVariant("WH-1000XM5"),
				"Alias":     dbus.MakeVariant("WH-1000XM5"),
				"Icon":      dbus.MakeVariant("audio-headset"),
				"Paired":    dbus.MakeVariant(true),
				"Trusted":   dbus.MakeVariant(true),
				"Connected": dbus.MakeVariant(true),
			},
		},
		"/org/bluez/hci0/dev_AA_BB_CC_DD_EE_02": {
			deviceInterface: {
				"Adapter":   dbus.MakeVariant(dbus.ObjectPath("/org/bluez/hci0")),
				"Address":   dbus.MakeVariant("AA:BB:CC:DD:EE:02"),
				"Name":      dbus.MakeVariant("Keychron K2"),
				"Alias":     dbus.MakeVariant("Desk Keyboard"),
				"Icon":      dbus.MakeVariant("input-keyboard"),
				"Paired":    dbus.MakeVariant(false),
				"Bonded":    dbus.MakeVariant(true),
				"Trusted":   dbus.MakeVariant(true),
				"Connected": dbus.MakeVariant(false),
			},
		},
		"/org/bluez/hci0/dev_AA_BB_CC_DD_EE_03": {
			deviceInterface: {
				"Adapter":   dbus.MakeVariant(dbus.ObjectPath("/org/bluez/hci0")),
				"Address":   dbus.MakeVariant("AA:BB:CC:DD:EE:03"),
				"Name":      dbus.MakeVariant("Nearby Beacon"),
				"Alias":     dbus.MakeVariant("Nearby Beacon"),
				"Paired":    dbus.MakeVariant(false),
				"Bonded":    dbus.MakeVariant(false),
				"Connected": dbus.MakeVariant(false),
			},
		},
	}
}

func TestListDevicesSortsAlphabeticallyAndFiltersUnpaired(t *testing.T) {
	c := NewClient(&fakeBackend{objects: sampleObjects()})
	devices, err := c.ListDevices(context.Background())
	if err != nil {
		t.Fatalf("ListDevices() error = %v", err)
	}
	if len(devices) != 2 {
		t.Fatalf("got %d devices, want 2: %+v", len(devices), devices)
	}
	// Alphabetical by DisplayName: "Desk Keyboard" before "WH-1000XM5" (no audio-first sorting).
	if devices[0].DisplayName() != "Desk Keyboard" || devices[0].Address != "AA:BB:CC:DD:EE:02" {
		t.Fatalf("devices[0] = %+v, want Desk Keyboard", devices[0])
	}
	if devices[1].DisplayName() != "WH-1000XM5" || devices[1].Address != "AA:BB:CC:DD:EE:01" || !devices[1].Connected {
		t.Fatalf("devices[1] = %+v, want WH-1000XM5 connected", devices[1])
	}
}

func TestListDevicesErrors(t *testing.T) {
	t.Run("no adapter", func(t *testing.T) {
		c := NewClient(&fakeBackend{objects: ManagedObjects{}})
		_, err := c.ListDevices(context.Background())
		if !errors.Is(err, ErrNoAdapter) {
			t.Fatalf("got err=%v, want ErrNoAdapter", err)
		}
	})

	t.Run("bluez service unknown", func(t *testing.T) {
		c := NewClient(&fakeBackend{
			objectsErr: dbus.Error{Name: dbusErrServiceUnknown, Body: []any{"The name org.bluez was not provided"}},
		})
		_, err := c.ListDevices(context.Background())
		if !errors.Is(err, ErrBlueZUnavailable) {
			t.Fatalf("got err=%v, want ErrBlueZUnavailable", err)
		}
	})
}

func TestFindDeviceMatching(t *testing.T) {
	objs := sampleObjects()
	objs["/org/bluez/hci0/dev_AA_BB_CC_DD_EE_04"] = map[string]map[string]dbus.Variant{
		deviceInterface: {
			"Address":   dbus.MakeVariant("AA:BB:CC:DD:EE:04"),
			"Name":      dbus.MakeVariant("Keychron M3"),
			"Alias":     dbus.MakeVariant("Keychron M3"),
			"Paired":    dbus.MakeVariant(true),
			"Connected": dbus.MakeVariant(false),
		},
	}
	c := NewClient(&fakeBackend{objects: objs})
	ctx := context.Background()

	// MAC with lowercase and dashes
	dev, err := c.FindDevice(ctx, "aa-bb-cc-dd-ee-01")
	if err != nil || dev.Address != "AA:BB:CC:DD:EE:01" {
		t.Fatalf("FindDevice(MAC) = (%+v, %v)", dev, err)
	}

	// Exact Name even when Alias differs
	dev, err = c.FindDevice(ctx, "keychron k2")
	if err != nil || dev.Address != "AA:BB:CC:DD:EE:02" {
		t.Fatalf("FindDevice(exact Name) = (%+v, %v)", dev, err)
	}

	// Unique substring
	dev, err = c.FindDevice(ctx, "1000xm5")
	if err != nil || dev.Address != "AA:BB:CC:DD:EE:01" {
		t.Fatalf("FindDevice(substring) = (%+v, %v)", dev, err)
	}

	// Ambiguous substring ("Keychron" matches both K2 and M3)
	_, err = c.FindDevice(ctx, "keychron")
	if !errors.Is(err, ErrAmbiguousDevice) {
		t.Fatalf("FindDevice(ambiguous) err = %v, want ErrAmbiguousDevice", err)
	}

	// Not found
	_, err = c.FindDevice(ctx, "nonexistent")
	if !errors.Is(err, ErrDeviceNotFound) {
		t.Fatalf("FindDevice(missing) err = %v, want ErrDeviceNotFound", err)
	}
}

func TestConnectAndDisconnect(t *testing.T) {
	ctx := context.Background()

	t.Run("connect success", func(t *testing.T) {
		b := &fakeBackend{objects: sampleObjects()}
		c := NewClient(b)
		dev, err := c.Connect(ctx, "Desk Keyboard")
		if err != nil {
			t.Fatalf("Connect() error = %v", err)
		}
		if b.calledOp != "Connect" || b.calledPath != "/org/bluez/hci0/dev_AA_BB_CC_DD_EE_02" {
			t.Fatalf("called (%q, %q), want (Connect, .../dev_AA_BB_CC_DD_EE_02)", b.calledOp, b.calledPath)
		}
		if !dev.Connected {
			t.Fatalf("expected returned device Connected=true")
		}
	})

	t.Run("connect already connected", func(t *testing.T) {
		b := &fakeBackend{
			objects:   sampleObjects(),
			methodErr: dbus.Error{Name: dbusErrAlreadyConnected, Body: []any{"Already Connected"}},
		}
		c := NewClient(b)
		_, err := c.Connect(ctx, "AA:BB:CC:DD:EE:01")
		if !errors.Is(err, ErrAlreadyConnected) {
			t.Fatalf("got err=%v, want ErrAlreadyConnected", err)
		}
	})

	t.Run("disconnect success", func(t *testing.T) {
		b := &fakeBackend{objects: sampleObjects()}
		c := NewClient(b)
		dev, err := c.Disconnect(ctx, "AA:BB:CC:DD:EE:01")
		if err != nil {
			t.Fatalf("Disconnect() error = %v", err)
		}
		if b.calledOp != "Disconnect" || b.calledPath != "/org/bluez/hci0/dev_AA_BB_CC_DD_EE_01" {
			t.Fatalf("called (%q, %q)", b.calledOp, b.calledPath)
		}
		if dev.Connected {
			t.Fatalf("expected returned device Connected=false")
		}
	})

	t.Run("disconnect not connected", func(t *testing.T) {
		b := &fakeBackend{
			objects:   sampleObjects(),
			methodErr: dbus.Error{Name: dbusErrNotConnected, Body: []any{"Not Connected"}},
		}
		c := NewClient(b)
		_, err := c.Disconnect(ctx, "AA:BB:CC:DD:EE:02")
		if !errors.Is(err, ErrNotConnected) {
			t.Fatalf("got err=%v, want ErrNotConnected", err)
		}
	})

	t.Run("adapter powered off", func(t *testing.T) {
		b := &fakeBackend{
			objects:   sampleObjects(),
			methodErr: dbus.Error{Name: dbusErrNotReady, Body: []any{"Resource Not Ready"}},
		}
		c := NewClient(b)
		_, err := c.Connect(ctx, "AA:BB:CC:DD:EE:02")
		if !errors.Is(err, ErrAdapterPoweredOff) {
			t.Fatalf("got err=%v, want ErrAdapterPoweredOff", err)
		}
	})

	t.Run("generic dbus failure keeps message", func(t *testing.T) {
		b := &fakeBackend{
			objects:   sampleObjects(),
			methodErr: dbus.Error{Name: "org.bluez.Error.Failed", Body: []any{"br-connection-page-timeout"}},
		}
		c := NewClient(b)
		_, err := c.Connect(ctx, "AA:BB:CC:DD:EE:02")
		if err == nil || !strings.Contains(err.Error(), "br-connection-page-timeout") {
			t.Fatalf("unexpected error: %v", err)
		}
	})
}
