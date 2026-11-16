#!/usr/bin/env bash
set -euo pipefail

# Debug helper for BlueHop's holder HTTP server (/status and signed /release).
#
# Usage:
#   PHONE_IP=<ip> ./scripts/holder-debug.sh status
#   PHONE_IP=<ip> DEVICE_MAC=<aa:bb:cc:dd:ee:ff> PSK_HEX=<64-hex> ./scripts/holder-debug.sh release
#
# Optional environment variables:
#   PORT          HTTP port on the holder (default: 8765)
#   REQUESTER_ID  Requester identifier included in the signed payload (default: pc-curl)
#
# To inspect the debug build's active PSK and device MAC via adb:
#   adb shell am broadcast -a org.hddq.bluehop.DEBUG_DUMP_PSK -n org.hddq.bluehop/.debug.ProtocolSelfTestReceiver
#   adb logcat -d -s BlueHopProtocol | tail -n 5

usage() {
  sed -n '3,16p' "$0" | sed 's/^# \{0,1\}//'
}

cmd="${1:-}"
port="${PORT:-8765}"
requester_id="${REQUESTER_ID:-pc-curl}"

case "$cmd" in
  status)
    phone_ip="${PHONE_IP:?Set PHONE_IP (e.g. PHONE_IP=192.168.1.50)}"
    curl -fsS "http://$phone_ip:$port/status" | python3 -m json.tool
    ;;

  release)
    phone_ip="${PHONE_IP:?Set PHONE_IP (e.g. PHONE_IP=192.168.1.50)}"
    device_mac="${DEVICE_MAC:?Set DEVICE_MAC (e.g. DEVICE_MAC=AA:BB:CC:DD:EE:FF)}"
    psk_hex="${PSK_HEX:?Set PSK_HEX (64 hex characters)}"

    read -r nonce body < <(
      DEVICE_MAC="$device_mac" PSK_HEX="$psk_hex" REQUESTER_ID="$requester_id" python3 -c '
import hashlib, hmac, json, os, secrets, time

psk = bytes.fromhex(os.environ["PSK_HEX"])
mac = os.environ["DEVICE_MAC"]
req_id = os.environ["REQUESTER_ID"]
ts = int(time.time())
nonce = secrets.token_hex(16)
canonical = f"v1\nrequest\n1\n{mac}\n{req_id}\n{ts}\n{nonce}"
sig = hmac.new(psk, canonical.encode(), hashlib.sha256).hexdigest()
payload = json.dumps({
    "version": 1,
    "deviceAddress": mac,
    "requesterId": req_id,
    "timestampEpochSec": ts,
    "nonce": nonce,
    "signature": sig,
}, separators=(",", ":"))
print(nonce, payload)
'
    )

    tmp_body="$(mktemp)"
    trap 'rm -f "$tmp_body"' EXIT
    http_code="$(
      curl -sS -o "$tmp_body" -w '%{http_code}' -X POST \
        -H "Content-Type: application/json" \
        -d "$body" \
        "http://$phone_ip:$port/release"
    )"

    echo "HTTP $http_code"
    RESPONSE_FILE="$tmp_body" PSK_HEX="$psk_hex" EXPECTED_NONCE="$nonce" python3 -c '
import hashlib, hmac, json, os

with open(os.environ["RESPONSE_FILE"], "r", encoding="utf-8") as f:
    raw = f.read()

try:
    resp = json.loads(raw)
except json.JSONDecodeError:
    print(raw)
    raise SystemExit(1)

print(json.dumps(resp, indent=2))

psk = bytes.fromhex(os.environ["PSK_HEX"])
ver = resp["version"]
status = resp["status"]
addr = resp["deviceAddress"]
ts = resp["timestampEpochSec"]
resp_nonce = resp["nonce"]
msg = resp.get("message", "")
canonical = f"v1\nresponse\n{ver}\n{status}\n{addr}\n{ts}\n{resp_nonce}\n{msg}"
expected_sig = hmac.new(psk, canonical.encode(), hashlib.sha256).hexdigest()
sig_ok = hmac.compare_digest(expected_sig, resp.get("signature", ""))
nonce_ok = resp_nonce == os.environ["EXPECTED_NONCE"]
print(f"signature_valid={sig_ok} nonce_match={nonce_ok}")
if not (sig_ok and nonce_ok):
    raise SystemExit(1)
'
    [ "$http_code" -eq 200 ]
    ;;

  *)
    usage >&2
    exit 2
    ;;
esac
