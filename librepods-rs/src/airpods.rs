//! AirPods BLE proximity-pairing message parser.
//!
//! Ported from the upstream LibrePods Linux implementation
//! (`linux/ble/blemanager.cpp`, `onDeviceDiscovered`). AirPods broadcast a
//! manufacturer-specific data block with Apple's company id `0x004C`. The first
//! byte is `0x07` (Proximity Pairing Message). Everything except the trailing
//! 16 encrypted bytes is readable without any pairing or AACP session, which is
//! exactly what we need on platforms where Apple's AACP protocol is unavailable
//! (old macOS, Windows).

/// Apple Inc. Bluetooth SIG company identifier.
pub const APPLE_MANUFACTURER_ID: u16 = 0x004C;

/// Proximity Pairing Message prefix (first byte of the manufacturer data).
const PPM_PREFIX: u8 = 0x07;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum AirPodsModel {
    AirPods1,
    AirPods2,
    AirPods3,
    AirPods4,
    AirPods4ANC,
    AirPodsMaxLightning,
    AirPodsMaxUSBC,
    AirPodsPro,
    AirPodsPro2Lightning,
    AirPodsPro2USBC,
    #[default]
    Unknown,
}

impl AirPodsModel {
    pub fn from_id(id: u16) -> Self {
        match id {
            0x0220 => AirPodsModel::AirPods1,
            0x0F20 => AirPodsModel::AirPods2,
            0x1320 => AirPodsModel::AirPods3,
            0x1920 => AirPodsModel::AirPods4,
            0x1B20 => AirPodsModel::AirPods4ANC,
            0x0A20 => AirPodsModel::AirPodsMaxLightning,
            0x1F20 => AirPodsModel::AirPodsMaxUSBC,
            0x0E20 => AirPodsModel::AirPodsPro,
            0x1420 => AirPodsModel::AirPodsPro2Lightning,
            0x2420 => AirPodsModel::AirPodsPro2USBC,
            _ => AirPodsModel::Unknown,
        }
    }

    pub fn name(&self) -> &'static str {
        match self {
            AirPodsModel::AirPods1 => "AirPods (1st gen)",
            AirPodsModel::AirPods2 => "AirPods (2nd gen)",
            AirPodsModel::AirPods3 => "AirPods (3rd gen)",
            AirPodsModel::AirPods4 => "AirPods (4th gen)",
            AirPodsModel::AirPods4ANC => "AirPods 4 (ANC)",
            AirPodsModel::AirPodsMaxLightning => "AirPods Max (Lightning)",
            AirPodsModel::AirPodsMaxUSBC => "AirPods Max (USB-C)",
            AirPodsModel::AirPodsPro => "AirPods Pro",
            AirPodsModel::AirPodsPro2Lightning => "AirPods Pro 2 (Lightning)",
            AirPodsModel::AirPodsPro2USBC => "AirPods Pro 2 (USB-C)",
            AirPodsModel::Unknown => "Unknown AirPods",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum ConnectionState {
    Disconnected,
    Idle,
    Music,
    Call,
    Ringing,
    HangingUp,
    #[default]
    Unknown,
}

impl ConnectionState {
    pub fn from_u8(v: u8) -> Self {
        match v {
            0x00 => ConnectionState::Disconnected,
            0x01 => ConnectionState::Idle,
            0x02 => ConnectionState::Music,
            0x03 => ConnectionState::Call,
            0x04 => ConnectionState::Ringing,
            0x05 => ConnectionState::HangingUp,
            _ => ConnectionState::Unknown,
        }
    }
}

fn color_name(id: u8) -> &'static str {
    match id {
        0x00 => "White",
        0x01 => "Black",
        0x02 => "Red",
        0x03 => "Blue",
        0x04 => "Pink",
        0x05 => "Gray",
        0x06 => "Silver",
        0x07 => "Gold",
        0x08 => "Rose Gold",
        0x09 => "Space Gray",
        0x0A => "Dark Blue",
        0x0B => "Light Blue",
        0x0C => "Yellow",
        _ => "Unknown",
    }
}

/// Parsed AirPods status from a single BLE advertisement.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct AirPodsStatus {
    pub name: String,
    pub address: String,
    pub model: AirPodsModel,
    /// Left pod battery percent (0-100), or `None` when unknown/disconnected.
    pub left_battery: Option<u8>,
    pub right_battery: Option<u8>,
    pub case_battery: Option<u8>,
    pub left_charging: bool,
    pub right_charging: bool,
    pub case_charging: bool,
    pub left_in_ear: bool,
    pub right_in_ear: bool,
    pub both_pods_in_case: bool,
    pub one_pod_in_case: bool,
    pub this_pod_in_case: bool,
    pub lid_open: bool,
    /// `true` when the left pod is the primary (bit 5 of status byte).
    pub primary_left: bool,
    pub connection_state: ConnectionState,
    pub color: String,
    /// Raw trailing 16-byte encrypted payload (AACP). Not decryptable here.
    pub encrypted_payload: Vec<u8>,
}

impl AirPodsStatus {
    /// Human-readable one-line summary, useful for tray tooltips / logs.
    pub fn summary(&self) -> String {
        let b = |v: Option<u8>| match v {
            Some(n) => format!("{n}%"),
            None => "—".to_string(),
        };
        let c = |charging: bool| if charging { "+" } else { "" };
        format!(
            "{} | L {}{} R {}{} Case {}{} | L-in-ear:{} R-in-ear:{} lid:{}",
            self.model.name(),
            b(self.left_battery),
            c(self.left_charging),
            b(self.right_battery),
            c(self.right_charging),
            b(self.case_battery),
            c(self.case_charging),
            self.left_in_ear,
            self.right_in_ear,
            self.lid_open,
        )
    }
}

/// Parse a raw Apple manufacturer data block (`0x004C`) into `AirPodsStatus`.
///
/// Returns `None` when the data is not an AirPods proximity-pairing message
/// (wrong prefix, too short, or the device is in pairing mode).
pub fn parse_advertisement(name: &str, address: &str, data: &[u8]) -> Option<AirPodsStatus> {
    // Must be at least 10 bytes and start with the PPM prefix.
    if data.len() < 10 || data[0] != PPM_PREFIX {
        return None;
    }
    // data[1] is the declared length; we ignore it and use the real length.
    // data[2] == 0x00 means the device is in pairing mode (different layout).
    if data[2] == 0x00 {
        return None;
    }

    let model_id = u16::from_be_bytes([data[3], data[4]]);
    let status = data[5];
    let pods_battery = data[6];
    let flags_and_case = data[7];
    let lid_indicator = data[8];
    let color_id = data[9];
    let connection_state = if data.len() > 10 { data[10] } else { 0 };

    let primary_left = (status & 0x20) != 0;
    let flipped = !primary_left;

    // Pod battery nibbles (each 0..=15, value 15 == unknown).
    let left_nibble = if flipped {
        (pods_battery >> 4) & 0x0F
    } else {
        pods_battery & 0x0F
    };
    let right_nibble = if flipped {
        pods_battery & 0x0F
    } else {
        (pods_battery >> 4) & 0x0F
    };
    let case_nibble = flags_and_case & 0x0F;

    let left_battery = if left_nibble == 15 {
        None
    } else {
        Some(left_nibble * 10)
    };
    let right_battery = if right_nibble == 15 {
        None
    } else {
        Some(right_nibble * 10)
    };
    let case_battery = if case_nibble == 15 {
        None
    } else {
        Some(case_nibble * 10)
    };

    // Charging flags live in the upper nibble of data[7].
    let flags = (flags_and_case >> 4) & 0x0F;
    let right_charging = if flipped {
        (flags & 0x01) != 0
    } else {
        (flags & 0x02) != 0
    };
    let left_charging = if flipped {
        (flags & 0x02) != 0
    } else {
        (flags & 0x01) != 0
    };
    let case_charging = (flags & 0x04) != 0;

    // Status byte (data[5]) flags.
    let this_pod_in_case = (status & 0x40) != 0;
    let one_pod_in_case = (status & 0x10) != 0;
    let both_pods_in_case = (status & 0x04) != 0;

    // In-ear detection follows XOR logic (ported verbatim).
    let xor_factor = flipped ^ this_pod_in_case;
    let left_in_ear = if xor_factor {
        (status & 0x08) != 0
    } else {
        (status & 0x02) != 0
    };
    let right_in_ear = if xor_factor {
        (status & 0x02) != 0
    } else {
        (status & 0x08) != 0
    };

    // Lid state: bit 3 of data[8]; only meaningful when a pod is in the case.
    let lid_state = (lid_indicator >> 3) & 0x01;
    let lid_open = this_pod_in_case && lid_state == 1;

    // Trailing 16 bytes are the encrypted AACP payload.
    let encrypted_payload = if data.len() >= 16 {
        data[data.len() - 16..].to_vec()
    } else {
        Vec::new()
    };

    Some(AirPodsStatus {
        name: if name.is_empty() {
            "AirPods".to_string()
        } else {
            name.to_string()
        },
        address: address.to_string(),
        model: AirPodsModel::from_id(model_id),
        left_battery,
        right_battery,
        case_battery,
        left_charging,
        right_charging,
        case_charging,
        left_in_ear,
        right_in_ear,
        both_pods_in_case,
        one_pod_in_case,
        this_pod_in_case,
        lid_open,
        primary_left,
        connection_state: ConnectionState::from_u8(connection_state),
        color: color_name(color_id).to_string(),
        encrypted_payload,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Build a minimal valid PPM with the given status/pods/flags bytes and a
    /// 16-byte encrypted tail, to exercise the parser.
    fn build_ppm(status: u8, pods: u8, flags_case: u8) -> Vec<u8> {
        let mut v = vec![
            PPM_PREFIX, // prefix
            0x10,       // length (unused by parser)
            0x01,       // paired
            0x13, 0x20, // model id 0x1320 -> AirPods3
            status, pods, flags_case,
            0x00, // lid indicator
            0x00, // color
            0x02, // connection state: Music
        ];
        // 16-byte encrypted tail.
        v.extend_from_slice(&[0u8; 16]);
        v
    }

    #[test]
    fn parses_basic_battery_unflipped() {
        // status=0x20: bit5=1 -> left primary (not flipped)
        // pods=0x32: lower nibble=2 (left 20%), upper nibble=3 (right 30%)
        // flags_case=0x59: flags=0x5 -> bit0 left-charging, bit2 case-charging; case nibble=9 (90%)
        let data = build_ppm(0x20, 0x32, 0x59);
        let s = parse_advertisement("AirPods", "AA:BB:CC:DD:EE:FF", &data).unwrap();
        assert_eq!(s.model, AirPodsModel::AirPods3);
        assert_eq!(s.left_battery, Some(20));
        assert_eq!(s.right_battery, Some(30));
        assert_eq!(s.case_battery, Some(90));
        assert!(s.left_charging);
        assert!(!s.right_charging);
        assert!(s.case_charging);
        assert!(s.primary_left);
    }

    #[test]
    fn parses_flipped_right_primary() {
        // status: bit5=0 -> right primary (flipped)
        let data = build_ppm(0x00, 0x42, 0x14);
        let s = parse_advertisement("", "AA:BB:CC:DD:EE:FF", &data).unwrap();
        assert!(!s.primary_left);
        // flipped: left nibble = upper (4 -> 40%), right nibble = lower (2 -> 20%)
        assert_eq!(s.left_battery, Some(40));
        assert_eq!(s.right_battery, Some(20));
    }

    #[test]
    fn rejects_pairing_mode() {
        let mut data = build_ppm(0x20, 0x32, 0x29);
        data[2] = 0x00; // pairing mode
        assert!(parse_advertisement("AirPods", "x", &data).is_none());
    }

    #[test]
    fn rejects_wrong_prefix() {
        let mut data = build_ppm(0x20, 0x32, 0x29);
        data[0] = 0x00;
        assert!(parse_advertisement("AirPods", "x", &data).is_none());
    }

    #[test]
    fn unknown_battery_is_none() {
        // nibble 15 == unknown
        let data = build_ppm(0x20, 0xFF, 0xFF);
        let s = parse_advertisement("AirPods", "x", &data).unwrap();
        assert_eq!(s.left_battery, None);
        assert_eq!(s.right_battery, None);
        assert_eq!(s.case_battery, None);
    }
}
