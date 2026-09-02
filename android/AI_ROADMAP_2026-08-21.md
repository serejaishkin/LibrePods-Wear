# LibrePods-Wear — Wear protocol roadmap (2026-08-31)

## Architecture decision

- System Bluetooth pairing/discovery remains the authority; the app does not implement a competing pairing UI.
- Wear app works with already paired AirPods and owns the connection/protocol layer after selection.
- Connection state, AACP transport, protocol parsing and diagnostics live in the Wear stack.
- The phone is not required by the Wear architecture.
- The current branch intentionally has the native/NDK Bluetooth build disabled; AACP uses the Android L2CAP API path in `AirPodsConnectionSession`.

## Current verified implementation

- Branch: `wearos/initial-cleanup`.
- Direct AACP L2CAP transport is owned by `AirPodsConnectionSession` / `WearBluetoothConnection`.
- AACP handshake state machine remains `IDLE -> HANDSHAKE_SENT -> FEATURES_SENT -> READY`.
- Battery AACP packets are validated and decoded into the single `AirPodsState` for left/right/case percentage and charging state.
- Ear-detection packets are validated and decoded.
- Existing BLE/GATT battery reader is retained as a secondary source.
- `BLEGattBatteryReader` is now explicitly a bounded, single-shot reader: it performs one connection/service-discovery/read sequence and does not own a polling loop.
- AACP RX logging records raw HEX before packet dispatch/parsing, plus opcode/type/length and parser summaries.
- Metadata opcode `0x1D` is decoded conservatively from the documented NUL-terminated fields, including manufacturer (`mfr`), model, firmware and serial fields.
- Metadata parse failures keep the complete raw packet available in Logcat instead of silently losing the information.
- Bounded AACP reconnect (3 attempts) remains implemented in `AirPodsController`.

## Current limitation / next implementation block

1. **AACP battery fallback integration** — the reader itself is now one-shot, but `AirPodsController` still contains the old periodic polling path. Replace that controller polling with a bounded fallback triggered after AACP READY when no AACP battery notification has arrived. It must update the existing `AirPodsState`, never replace the AACP source, and be cancelled on disconnect.
2. **Charging from fallback** — do not invent charging flags from an unverified GATT payload. Keep the existing AACP charging flags authoritative and only add fallback charging when a verified GATT source is available.
3. **AACP stream framing** — finish conservative framing for fragmented/coalesced L2CAP reads. Do not treat Bluetooth `read()` boundaries as protocol frame boundaries.
4. **Handshake framing** — make handshake ACK and feature ACK survive split/coalesced reads.
5. **Battery/status** — verify stable left/right/case percentages + charging + connected state on hardware, with AACP preferred over fallback.
6. **Ear detection** — verify stable left/right in-ear state and avoid stale BLE overwrites after AACP is READY.
7. **Listening mode** — verified Off=1, ANC=2, Transparency=3 writes exist; add verified status/read handling.
8. **Conversation Awareness** — verified enable/disable write exists; add verified status handling.
9. **Stem/button control** — implement verified command/status handling.
10. **Connected devices / ownership** — decode and expose verified state.
11. **Reconnect** — validate bounded reconnect and clean AACP session restart on hardware.
12. **ATT** — keep optional until AACP status is stable.
13. **Protocol test vectors** — add captured packet fixtures; never guess unknown layouts.
14. **Wear UI** — keep UI secondary; expose connection, battery, charging, protocol stage and diagnostic packet details without making diagnostics the production UI.

## Diagnostic logging contract

For incoming AACP data, Logcat should show:

- raw packet HEX before parsing;
- opcode / record type;
- packet length;
- key verified parsed fields;
- manufacturer when present in metadata;
- parse result or explicit parse error.

No personal data should be added to the production UI merely for diagnostics. Raw packet logging is intended for development/reverse-engineering and should be used with debug Logcat.

## Acceptance target

`Paired AirPods -> Bluetooth connected -> AACP CONNECTED -> AACP READY -> Left/Right/Case battery + charging -> ear detection -> verified ANC/Transparency/Off -> clean disconnect/reconnect`.

Only after that is stable should the project expand the verified AACP control surface and secondary ATT/Find My functionality.
