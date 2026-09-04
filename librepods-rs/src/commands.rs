//! AirPods AACP control commands.
//!
//! Ported from upstream `airpods_packets.h` (NoiseControl, ConversationalAwareness,
//! HearingAid, Rename, OneBudANCMode, VolumeSwipe, AdaptiveVolume, AllowOffOption)
//! and `BasicControlCommand.hpp`.

use crate::aacp::{create_control_command, parse_control_active};

// ── Noise Control Mode ────────────────────────────────────────────────

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum NoiseControlMode {
    Off = 0,
    NoiseCancellation = 1,
    Transparency = 2,
    #[default]
    Adaptive = 3,
}

impl NoiseControlMode {
    pub fn from_u8(v: u8) -> Option<Self> {
        match v {
            0 => Some(NoiseControlMode::Off),
            1 => Some(NoiseControlMode::NoiseCancellation),
            2 => Some(NoiseControlMode::Transparency),
            3 => Some(NoiseControlMode::Adaptive),
            _ => None,
        }
    }

    pub fn name(&self) -> &'static str {
        match self {
            NoiseControlMode::Off => "Off",
            NoiseControlMode::NoiseCancellation => "Noise Cancellation",
            NoiseControlMode::Transparency => "Transparency",
            NoiseControlMode::Adaptive => "Adaptive",
        }
    }
}

/// Header byte for noise control commands.
const NOISE_CONTROL_ID: u8 = 0x0D;

pub fn noise_control_off() -> Vec<u8> {
    create_control_command(NOISE_CONTROL_ID, &[0x01])
}

pub fn noise_control_anc() -> Vec<u8> {
    create_control_command(NOISE_CONTROL_ID, &[0x02])
}

pub fn noise_control_transparency() -> Vec<u8> {
    create_control_command(NOISE_CONTROL_ID, &[0x03])
}

pub fn noise_control_adaptive() -> Vec<u8> {
    create_control_command(NOISE_CONTROL_ID, &[0x04])
}

pub fn noise_control_packet(mode: NoiseControlMode) -> Vec<u8> {
    match mode {
        NoiseControlMode::Off => noise_control_off(),
        NoiseControlMode::NoiseCancellation => noise_control_anc(),
        NoiseControlMode::Transparency => noise_control_transparency(),
        NoiseControlMode::Adaptive => noise_control_adaptive(),
    }
}

/// Parse noise control mode from a response packet (11 bytes, header at byte 7).
pub fn parse_noise_control(data: &[u8]) -> Option<NoiseControlMode> {
    let active = parse_control_active(data)?;
    NoiseControlMode::from_u8(active.wrapping_sub(1))
}

// ── Conversational Awareness ───────────────────────────────────────────

const CA_ID: u8 = 0x28;
pub const CA_DATA_HEADER: &[u8] = &[0x04, 0x00, 0x04, 0x00, 0x4B, 0x00, 0x02, 0x00, 0x01];

pub fn conversational_awareness_enable() -> Vec<u8> {
    create_control_command(CA_ID, &[0x01])
}

pub fn conversational_awareness_disable() -> Vec<u8> {
    create_control_command(CA_ID, &[0x02])
}

pub fn parse_conversational_awareness(data: &[u8]) -> Option<bool> {
    let active = parse_control_active(data)?;
    match active {
        0x01 => Some(true),
        0x02 => Some(false),
        _ => None,
    }
}

// ── One Bud ANC Mode ──────────────────────────────────────────────────

const ONE_BUD_ANC_ID: u8 = 0x1B;

pub fn one_bud_anc_enable() -> Vec<u8> {
    create_control_command(ONE_BUD_ANC_ID, &[0x01])
}

pub fn one_bud_anc_disable() -> Vec<u8> {
    create_control_command(ONE_BUD_ANC_ID, &[0x02])
}

pub fn parse_one_bud_anc(data: &[u8]) -> Option<bool> {
    let active = parse_control_active(data)?;
    match active {
        0x01 => Some(true),
        0x02 => Some(false),
        _ => None,
    }
}

// ── Volume Swipe ───────────────────────────────────────────────────────

const VOLUME_SWIPE_ID: u8 = 0x25;

pub fn volume_swipe_enable() -> Vec<u8> {
    create_control_command(VOLUME_SWIPE_ID, &[0x01])
}

pub fn volume_swipe_disable() -> Vec<u8> {
    create_control_command(VOLUME_SWIPE_ID, &[0x02])
}

pub fn parse_volume_swipe(data: &[u8]) -> Option<bool> {
    let active = parse_control_active(data)?;
    match active {
        0x01 => Some(true),
        0x02 => Some(false),
        _ => None,
    }
}

/// Volume swipe interval packet (command 0x23).
pub fn volume_swipe_interval(interval: u8) -> Vec<u8> {
    create_control_command(0x23, &[interval])
}

// ── Adaptive Volume ────────────────────────────────────────────────────

const ADAPTIVE_VOLUME_ID: u8 = 0x26;

pub fn adaptive_volume_enable() -> Vec<u8> {
    create_control_command(ADAPTIVE_VOLUME_ID, &[0x01])
}

pub fn adaptive_volume_disable() -> Vec<u8> {
    create_control_command(ADAPTIVE_VOLUME_ID, &[0x02])
}

pub fn parse_adaptive_volume(data: &[u8]) -> Option<bool> {
    let active = parse_control_active(data)?;
    match active {
        0x01 => Some(true),
        0x02 => Some(false),
        _ => None,
    }
}

// ── Hearing Assist ─────────────────────────────────────────────────────

const HEARING_ASSIST_ID: u8 = 0x33;

pub fn hearing_assist_enable() -> Vec<u8> {
    create_control_command(HEARING_ASSIST_ID, &[0x01])
}

pub fn hearing_assist_disable() -> Vec<u8> {
    create_control_command(HEARING_ASSIST_ID, &[0x02])
}

pub fn parse_hearing_assist(data: &[u8]) -> Option<bool> {
    let active = parse_control_active(data)?;
    match active {
        0x01 => Some(true),
        0x02 => Some(false),
        _ => None,
    }
}

// ── Hearing Aid ────────────────────────────────────────────────────────

const HEARING_AID_ID: u8 = 0x2C;
pub const HEARING_AID_HEADER: &[u8] = &[0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x2C];

pub fn hearing_aid_enable() -> Vec<u8> {
    create_control_command(HEARING_AID_ID, &[0x01, 0x01])
}

pub fn hearing_aid_disable() -> Vec<u8> {
    create_control_command(HEARING_AID_ID, &[0x02, 0x02])
}

pub fn parse_hearing_aid(data: &[u8]) -> Option<bool> {
    if !data.starts_with(HEARING_AID_HEADER) || data.len() < HEARING_AID_HEADER.len() + 2 {
        return None;
    }
    let b1 = data[HEARING_AID_HEADER.len()];
    let b2 = data[HEARING_AID_HEADER.len() + 1];
    if b1 == 0x01 && b2 == 0x01 {
        Some(true)
    } else if b1 == 0x02 || b2 == 0x02 {
        Some(false)
    } else {
        None
    }
}

// ── Allow Off Option ───────────────────────────────────────────────────

const ALLOW_OFF_OPTION_ID: u8 = 0x34;

pub fn allow_off_option_enable() -> Vec<u8> {
    create_control_command(ALLOW_OFF_OPTION_ID, &[0x01])
}

pub fn allow_off_option_disable() -> Vec<u8> {
    create_control_command(ALLOW_OFF_OPTION_ID, &[0x02])
}

pub fn parse_allow_off_option(data: &[u8]) -> Option<bool> {
    let active = parse_control_active(data)?;
    match active {
        0x01 => Some(true),
        0x02 => Some(false),
        _ => None,
    }
}

// ── Adaptive Noise ─────────────────────────────────────────────────────

const ADAPTIVE_NOISE_HEADER: &[u8] = &[0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x2E];

/// Set adaptive noise level (0-100).
pub fn adaptive_noise_level(level: u8) -> Vec<u8> {
    let mut pkt = ADAPTIVE_NOISE_HEADER.to_vec();
    pkt.push(level);
    pkt.extend_from_slice(&[0x00, 0x00, 0x00]);
    pkt
}

// ── Rename ─────────────────────────────────────────────────────────────

const RENAME_HEADER: &[u8] = &[0x04, 0x00, 0x04, 0x00, 0x1A, 0x00, 0x01];

/// Build a rename command packet.
pub fn rename(name: &str) -> Option<Vec<u8>> {
    if name.is_empty() || name.len() > 32 {
        return None;
    }
    let name_bytes = name.as_bytes();
    let mut pkt = RENAME_HEADER.to_vec();
    pkt.push(name_bytes.len() as u8);
    pkt.push(0x00); // null separator
    pkt.extend_from_slice(name_bytes);
    Some(pkt)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_noise_control_packets() {
        let off = noise_control_off();
        assert_eq!(off[6], 0x0D);
        assert_eq!(off[7], 0x01);

        let anc = noise_control_anc();
        assert_eq!(anc[7], 0x02);

        let trans = noise_control_transparency();
        assert_eq!(trans[7], 0x03);

        let adap = noise_control_adaptive();
        assert_eq!(adap[7], 0x04);
    }

    #[test]
    fn test_parse_noise_control() {
        let mut data = vec![0u8; 11];
        data[..6].copy_from_slice(crate::aacp::CONTROL_HEADER);
        data[7] = 0x03; // Transparency (0x02 + 1)
        assert_eq!(parse_noise_control(&data), Some(NoiseControlMode::Transparency));
    }

    #[test]
    fn test_conversational_awareness() {
        let enable = conversational_awareness_enable();
        assert_eq!(enable[6], 0x28);
        assert_eq!(enable[7], 0x01);

        let disable = conversational_awareness_disable();
        assert_eq!(disable[7], 0x02);
    }

    #[test]
    fn test_rename() {
        let pkt = rename("MyPods").unwrap();
        assert_eq!(&pkt[..7], RENAME_HEADER);
        assert_eq!(pkt[7], 6); // length
        assert_eq!(pkt[8], 0x00); // null separator
        assert_eq!(&pkt[9..], b"MyPods");
    }

    #[test]
    fn test_rename_rejects_empty() {
        assert!(rename("").is_none());
    }

    #[test]
    fn test_rename_rejects_too_long() {
        assert!(rename(&"x".repeat(33)).is_none());
    }

    #[test]
    fn test_adaptive_noise_level() {
        let pkt = adaptive_noise_level(75);
        assert_eq!(&pkt[..7], ADAPTIVE_NOISE_HEADER);
        assert_eq!(pkt[7], 75);
    }
}
