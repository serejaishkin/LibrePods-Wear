//! Ear detection parser for AACP packets.
//!
//! Ported from upstream `eardetection.hpp`. Parses the ear detection status
//! from AACP L2CAP packets (header `04 00 04 00 06 00`).

use crate::aacp::EAR_DETECTION_HEADER;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum EarDetectionStatus {
    #[default]
    Disconnected,
    InEar,
    NotInEar,
    InCase,
}

impl EarDetectionStatus {
    fn from_byte(b: u8) -> Self {
        match b {
            0x00 => EarDetectionStatus::InEar,
            0x01 => EarDetectionStatus::NotInEar,
            0x02 => EarDetectionStatus::InCase,
            _ => EarDetectionStatus::Disconnected,
        }
    }
}

#[derive(Debug, Clone, Default)]
pub struct EarDetection {
    pub primary: EarDetectionStatus,
    pub secondary: EarDetectionStatus,
}

impl EarDetection {
    /// Parse an ear detection packet (8 bytes, header at bytes 0-5, status at bytes 6-7).
    pub fn parse(&mut self, data: &[u8]) -> bool {
        if data.len() < 8 || !data.starts_with(EAR_DETECTION_HEADER) {
            return false;
        }
        self.primary = EarDetectionStatus::from_byte(data[6]);
        self.secondary = EarDetectionStatus::from_byte(data[7]);
        true
    }

    /// Override ear detection from BLE proximity data (simpler boolean).
    pub fn override_from_ble(&mut self, primary_in_ear: bool, secondary_in_ear: bool) {
        self.primary = if primary_in_ear { EarDetectionStatus::InEar } else { EarDetectionStatus::NotInEar };
        self.secondary = if secondary_in_ear { EarDetectionStatus::InEar } else { EarDetectionStatus::NotInEar };
    }

    pub fn is_primary_in_ear(&self) -> bool {
        self.primary == EarDetectionStatus::InEar
    }

    pub fn is_secondary_in_ear(&self) -> bool {
        self.secondary == EarDetectionStatus::InEar
    }

    pub fn one_or_more_in_ear(&self) -> bool {
        self.is_primary_in_ear() || self.is_secondary_in_ear()
    }

    pub fn one_or_more_in_case(&self) -> bool {
        self.primary == EarDetectionStatus::InCase || self.secondary == EarDetectionStatus::InCase
    }

    pub fn summary(&self) -> String {
        let fmt = |s: &EarDetectionStatus| match s {
            EarDetectionStatus::InEar => "In Ear",
            EarDetectionStatus::NotInEar => "Out",
            EarDetectionStatus::InCase => "In Case",
            EarDetectionStatus::Disconnected => "—",
        };
        format!("L: {} R: {}", fmt(&self.primary), fmt(&self.secondary))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_ear_detection() {
        let mut data = vec![0u8; 8];
        data[..6].copy_from_slice(EAR_DETECTION_HEADER);
        data[6] = 0x00; // primary: InEar
        data[7] = 0x01; // secondary: NotInEar

        let mut ed = EarDetection::default();
        assert!(ed.parse(&data));
        assert_eq!(ed.primary, EarDetectionStatus::InEar);
        assert_eq!(ed.secondary, EarDetectionStatus::NotInEar);
        assert!(ed.is_primary_in_ear());
        assert!(!ed.is_secondary_in_ear());
        assert!(ed.one_or_more_in_ear());
    }

    #[test]
    fn test_parse_too_short() {
        let data = vec![0u8; 5];
        let mut ed = EarDetection::default();
        assert!(!ed.parse(&data));
    }

    #[test]
    fn test_override_from_ble() {
        let mut ed = EarDetection::default();
        ed.override_from_ble(true, false);
        assert!(ed.is_primary_in_ear());
        assert!(!ed.is_secondary_in_ear());
    }

    #[test]
    fn test_in_case() {
        let mut data = vec![0u8; 8];
        data[..6].copy_from_slice(EAR_DETECTION_HEADER);
        data[6] = 0x02; // primary: InCase
        data[7] = 0x00; // secondary: InEar

        let mut ed = EarDetection::default();
        ed.parse(&data);
        assert!(ed.one_or_more_in_case());
        assert!(ed.one_or_more_in_ear()); // secondary is still in ear
    }
}
