//! AirPods AACP (Apple Audio Control Protocol) packet definitions.
//!
//! Ported from upstream `airpods_packets.h` and `BasicControlCommand.hpp`.
//! AACP runs over L2CAP PSM 0x1001 with an ECDH + AES-CCM encrypted channel.
//! On platforms where L2CAP is unavailable (macOS, Windows), these packets are
//! useful only as data structures — the actual transport is Linux-only via BlueZ.

/// AACP service UUID (used for SDP / L2CAP service discovery).
pub const AACP_UUID: &str = "74EC2172-0BAD-4D01-8F77-997B2BE0722A";

/// L2CAP PSM for AACP.
pub const AACP_PSM: u16 = 0x1001;

// ── Connection packets ────────────────────────────────────────────────

/// Initial handshake sent after L2CAP connect.
pub const HANDSHAKE: &[u8] = &[
    0x00, 0x00, 0x04, 0x00, 0x01, 0x00, 0x02, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
];

/// Set specific features (sent after handshake ACK).
pub const SET_SPECIFIC_FEATURES: &[u8] = &[
    0x04, 0x00, 0x04, 0x00, 0x4d, 0x00, 0xd7, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
];

/// Request all notification packets.
pub const REQUEST_NOTIFICATIONS: &[u8] = &[
    0x04, 0x00, 0x04, 0x00, 0x0f, 0x00, 0xff, 0xff,
    0xff, 0xff, 0xff,
];

/// AirPods disconnected signal.
pub const AIRPODS_DISCONNECTED: &[u8] = &[0x00, 0x01, 0x00, 0x00];

// ── Parse headers ─────────────────────────────────────────────────────

pub const HANDSHAKE_ACK: &[u8] = &[0x01, 0x00, 0x04, 0x00];
pub const FEATURES_ACK: &[u8] = &[0x04, 0x00, 0x04, 0x00, 0x2b, 0x00];
pub const BATTERY_STATUS_HEADER: &[u8] = &[0x04, 0x00, 0x04, 0x00, 0x04, 0x00];
pub const EAR_DETECTION_HEADER: &[u8] = &[0x04, 0x00, 0x04, 0x00, 0x06, 0x00];
pub const METADATA_HEADER: &[u8] = &[0x04, 0x00, 0x04, 0x00, 0x1d];

// ── Magic Pairing ─────────────────────────────────────────────────────

pub const REQUEST_MAGIC_CLOUD_KEYS: &[u8] = &[0x04, 0x00, 0x04, 0x00, 0x30, 0x00, 0x05, 0x00];
pub const MAGIC_CLOUD_KEYS_HEADER: &[u8] = &[0x04, 0x00, 0x04, 0x00, 0x31, 0x00, 0x02];

// ── Phone communication (cross-device) ────────────────────────────────

pub const PHONE_NOTIFICATION: &[u8] = &[0x00, 0x04, 0x00, 0x01];
pub const PHONE_CONNECTED: &[u8] = &[0x00, 0x01, 0x00, 0x01];
pub const PHONE_DISCONNECTED: &[u8] = &[0x00, 0x01, 0x00, 0x00];
pub const PHONE_STATUS_REQUEST: &[u8] = &[0x00, 0x02, 0x00, 0x03];
pub const PHONE_DISCONNECT_REQUEST: &[u8] = &[0x00, 0x02, 0x00, 0x00];

// ── Control command header ────────────────────────────────────────────

/// Control command header prefix (all commands start with this).
pub const CONTROL_HEADER: &[u8] = &[0x04, 0x00, 0x04, 0x00, 0x09, 0x00];

/// Build a control command packet.
pub fn create_control_command(identifier: u8, data: &[u8]) -> Vec<u8> {
    let mut pkt = CONTROL_HEADER.to_vec();
    pkt.push(identifier);
    pkt.extend_from_slice(data);
    pkt
}

/// Parse the active value (byte at index 7) from a control response.
pub fn parse_control_active(data: &[u8]) -> Option<u8> {
    if data.len() > 7 && data.starts_with(CONTROL_HEADER) {
        Some(data[7])
    } else {
        None
    }
}

// ── Metadata parsing ──────────────────────────────────────────────────

/// Parsed AirPods metadata from the METADATA packet.
#[derive(Debug, Clone, Default)]
pub struct Metadata {
    pub name: String,
    pub model_number: String,
    pub manufacturer: String,
}

/// Parse a METADATA packet into device info.
pub fn parse_metadata(data: &[u8]) -> Option<Metadata> {
    if !data.starts_with(METADATA_HEADER) {
        return None;
    }
    let mut pos = METADATA_HEADER.len();
    if data.len() < pos + 6 {
        return None;
    }
    pos += 6; // skip 6 reserved bytes

    let extract_string = |pos: &mut usize| -> String {
        if *pos >= data.len() {
            return String::new();
        }
        let start = *pos;
        while *pos < data.len() && data[*pos] != 0 {
            *pos += 1;
        }
        let s = String::from_utf8_lossy(&data[start..*pos]).to_string();
        if *pos < data.len() {
            *pos += 1; // skip null terminator
        }
        s
    };

    Some(Metadata {
        name: extract_string(&mut pos),
        model_number: extract_string(&mut pos),
        manufacturer: extract_string(&mut pos),
    })
}

// ── Magic Cloud Keys parsing ──────────────────────────────────────────

/// Parsed Magic Cloud Keys (IRK + encryption key).
#[derive(Debug, Clone, Default)]
pub struct MagicCloudKeys {
    pub magic_acc_irk: Vec<u8>,
    pub magic_acc_enc_key: Vec<u8>,
}

/// Parse a Magic Cloud Keys response packet.
pub fn parse_magic_cloud_keys(data: &[u8]) -> Option<MagicCloudKeys> {
    if data.len() < 47 || !data.starts_with(MAGIC_CLOUD_KEYS_HEADER) {
        return None;
    }
    let mut idx = MAGIC_CLOUD_KEYS_HEADER.len();

    // First TLV block (IRK)
    if data.get(idx)? != &0x01 {
        return None;
    }
    idx += 1;
    let len1 = ((*data.get(idx)? as u16) << 8 | *data.get(idx + 1)? as u16) as usize;
    if len1 != 16 {
        return None;
    }
    idx += 3; // skip length (2 bytes) + reserved (1 byte)
    let irk = data[idx..idx + 16].to_vec();
    idx += 16;

    // Second TLV block (EncKey)
    if data.get(idx)? != &0x04 {
        return None;
    }
    idx += 1;
    let len2 = ((*data.get(idx)? as u16) << 8 | *data.get(idx + 1)? as u16) as usize;
    if len2 != 16 {
        return None;
    }
    idx += 3;
    let enc_key = data[idx..idx + 16].to_vec();

    Some(MagicCloudKeys {
        magic_acc_irk: irk,
        magic_acc_enc_key: enc_key,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_create_control_command() {
        let cmd = create_control_command(0x0D, &[0x01]);
        assert_eq!(&cmd[..6], CONTROL_HEADER);
        assert_eq!(cmd[6], 0x0D);
        assert_eq!(cmd[7], 0x01);
    }

    #[test]
    fn test_parse_control_active() {
        let mut data = vec![0u8; 12];
        data[..6].copy_from_slice(CONTROL_HEADER);
        data[7] = 0x03;
        assert_eq!(parse_control_active(&data), Some(0x03));
    }

    #[test]
    fn test_parse_metadata() {
        // Build a minimal metadata packet
        let mut data = METADATA_HEADER.to_vec();
        data.extend_from_slice(&[0u8; 6]); // reserved
        data.extend_from_slice(b"AirPods Pro");
        data.push(0);
        data.extend_from_slice(b"A3048");
        data.push(0);
        data.extend_from_slice(b"Apple Inc.");
        data.push(0);
        let meta = parse_metadata(&data).unwrap();
        assert_eq!(meta.name, "AirPods Pro");
        assert_eq!(meta.model_number, "A3048");
        assert_eq!(meta.manufacturer, "Apple Inc.");
    }

    #[test]
    fn test_parse_magic_cloud_keys() {
        let mut data = MAGIC_CLOUD_KEYS_HEADER.to_vec();
        data.push(0x01); // IRK tag
        data.push(0x00);
        data.push(16); // length
        data.push(0x00); // reserved
        data.extend_from_slice(&[0xAA; 16]); // IRK
        data.push(0x04); // EncKey tag
        data.push(0x00);
        data.push(16); // length
        data.push(0x00); // reserved
        data.extend_from_slice(&[0xBB; 16]); // EncKey
        let keys = parse_magic_cloud_keys(&data).unwrap();
        assert_eq!(keys.magic_acc_irk, vec![0xAA; 16]);
        assert_eq!(keys.magic_acc_enc_key, vec![0xBB; 16]);
    }

    #[test]
    fn test_parse_metadata_too_short() {
        let data = METADATA_HEADER.to_vec();
        assert!(parse_metadata(&data).is_none());
    }
}
