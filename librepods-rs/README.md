# librepods-rs

Cross-platform AirPods status reader for **macOS** and **Windows**, written in Rust.
It is a port of the [LibrePods](https://github.com/librepods-org/librepods) Linux
implementation (`linux/ble/blemanager.cpp`), adapted for platforms where Apple's
private **AACP** protocol is not available.

## What it does

AirPods periodically broadcast a Bluetooth Low Energy **proximity-pairing message**
in Apple's manufacturer data block (`company id 0x004C`). The unencrypted portion of
that message already contains:

- Left / Right pod battery %, and charging state
- Case battery % and charging state
- In-ear detection (left / right)
- Lid open/closed
- Which pod is primary, model, color

`librepods-rs` passively scans for those broadcasts and decodes them — no pairing,
no GATT, no AACP session required.

## What it cannot do (by design)

On old macOS and Windows there is **no AACP** (Apple's authenticated control
protocol). That means the following are unavailable here, exactly as on the Wear OS
fork:

- Active Noise Cancellation / Transparency toggles
- Ear-detection configuration, conversation awareness
- Firmware / serial number queries requiring the encrypted session

Those require decrypting the trailing 16-byte payload with the per-device proximity
key, which Apple only exposes through AACP (modern macOS private framework /
not on Windows / not on Wear OS).

## Architecture

```
main.rs      CLI entry: scans, dedupes by address, prints status on change
ble.rs       Cross-platform BLE scanner (btleplug: BlueZ / WinRT / CoreBluetooth)
airpods.rs   Pure parser: Apple 0x004C manufacturer-data -> AirPodsStatus (+ tests)
```

The parser in `airpods.rs` is a line-by-line port of the Linux
`BleManager::onDeviceDiscovered` nibble/flag logic and is covered by unit tests
that run without any Bluetooth hardware.

## Build & run

```bash
cargo build --release
# CLI live view (restarts scan if it ever drops):
cargo run --release
# single-shot for scripts/tests (exits after first device or 20s):
cargo run --release -- --once
# system tray app (Windows / macOS): icon in the notification area / status bar,
# tooltip shows the live battery summary, right-click -> Quit:
cargo run --release -- --tray
```

On Windows this links the WinRT Bluetooth backend; on macOS it uses CoreBluetooth
(via `btleplug`). No extra system dependencies are required for scanning.

### macOS packaging note

On macOS the `--tray` binary must be launched as a proper `.app` bundle
(`cargo bundle`, `macoslu`, or `tray-icon`'s packaging example) and run from the
main thread — a bare `cargo run` binary will not show a status-bar item because
macOS requires a bundled `NSApplication`. The code already sets the activation
policy to `Accessory` (status-bar only, no Dock icon) on macOS.

## Next steps

- **Richer tray UI**: add a left-click menu showing per-pod/case battery bars and
  in-ear/lid state (the `AirPodsStatus` struct already carries everything).
- **Encrypted payload**: optionally collect the 16-byte tail and, for users who
  supply their proximity key (exported from a paired phone), decrypt it for the
  advanced fields — same approach as the Android/Wear implementation.
- **Encrypted payload**: optionally collect the 16-byte tail and, for users who
  supply their proximity key (exported from a paired phone), decrypt it for the
  advanced fields — same approach as the Android/Wear implementation.
- **Single binary per OS**: `cargo build --target <triple>` produces a native
  executable for each platform; no runtime needed.
