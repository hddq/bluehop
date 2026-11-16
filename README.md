# BlueHop

BlueHop brings multipoint-like switching to Bluetooth devices (such as headphones) that don't natively support multipoint. Each host device running the app (and a planned Linux client) exposes a simple API server while connected to the Bluetooth device. Tapping the button on another device sends a request to that server asking the currently connected host to disconnect, so the device you tapped on can connect right away.
