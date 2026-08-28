# LibrePods-Wear — Wear protocol roadmap (2026-08-28)

## Architecture decision

- System Bluetooth pairing/discovery remains the authority; the app does not implement a competing pairing UI.
- Wear app works with already paired AirPods and owns the connection/protocol layer after selection.
- Connection state, AACP transport, protocol parsing and diagnostics live in the Wear stack.
- The phone is not required by the Wear architecture.
- The current branch intentionally has the native/NDK Bluetooth build disabled; AACP uses the Android L2CAP API path in `AirPodsConnectionSession`.

## Current verified implementation

- Branch: `wearos/initial-cleanup`.
- Latest user baseline commit was `fix connect` (`fa4e2c4`); subsequent commits in this block only extend diagnostics/protocol parsing.
- Direct AACP L2CAP transport is owned by `AirPodsConnectionSession` / `WearBluetoothConnection`.
- AACP handshake state machine remains `IDLE -> HANDSHAKE_SENT -> FEATURES_SENT -> READY`.
- Battery AACP packets are validated and decoded into the single `AirPodsState` for left/right/case percentage and charging state.
- Ear-detection packets are validated and decoded.
- Existing BLE/GATT battery reader remains available as a secondary source when the controller is operating outside AACP.
- Bounded AACP reconnect (3 attempts) remains implemented in `AirPodsController`.
- AACP RX logging now records raw HEX before packet dispatch/parsing, plus opcode/type/length and parser summaries.
- Metadata opcode `0x1D` is now decoded conservatively from the documented NUL-terminated fields, including manufacturer (`mfr`), model, firmware and serial fields.
- Metadata parse failures keep the complete raw packet available in Logcat instead of silently losing the information.

## Current limitation / next implementation block

1. **AACP battery fallback** — integrate the existing `BLEGattBatteryReader` into `AirPodsController` specifically as a bounded fallback after AACP READY when no AACP battery notification arrives. It must update the existing `AirPodsState`, never replace the AACP source, never create a second state, and never run an endless polling loop.
2. **AACP stream framing** — finish conservative framing for fragmented/coalesced L2CAP reads. Do not treat Bluetooth `read()` boundaries as protocol frame boundaries.
3. **Handshake framing** — make handshake ACK and feature ACK survive split/coalesced reads.
4. **Battery/status** — verify stable left/right/case percentages + charging + connected state on hardware, with AACP preferred over fallback.
5. **Ear detection** — verify stable left/right in-ear state and avoid stale BLE overwrites after AACP is READY.
6. **Listening mode** — verified Off=1, ANC=2, Transparency=3 writes exist; add verified status/read handling.
7. **Conversation Awareness** — verified enable/disable write exists; add verified status handling.
8. **Stem/button control** — implement verified command/status handling.
9. **Connected devices / ownership** — decode and expose verified state.
10. **Reconnect** — validate bounded reconnect and clean AACP session restart on hardware.
11. **ATT** — keep optional until AACP status is stable.
12. **Protocol test vectors** — add captured packet fixtures; never guess unknown layouts.
13. **Wear UI** — keep UI secondary; expose connection, battery, charging, protocol stage and diagnostic packet details without making diagnostics the production UI.

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
