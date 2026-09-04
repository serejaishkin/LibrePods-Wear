//! Battery status parser for AACP packets.
//!
//! Ported from upstream `battery.hpp`. Handles both:
//! - AACP battery status packets (from the encrypted L2CAP channel)
//! - Encrypted BLE proximity battery packets (via IRK-verified BLE advertisements)

use crate::aacp::BATTERY_STATUS_HEADER;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum BatteryComponent {
    #[default]
    Right = 0x02,
    Headset = 0x01,
    Left = 0x04,
    Case = 0x08,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum BatteryStatus {
    #[default]
    Disconnected = 0x04,
    Charging = 0x01,
    Discharging = 0x02,
}

#[derive(Debug, Clone, Copy, Default)]
pub struct ComponentState {
    pub level: u8,
    pub status: BatteryStatus,
}

#[derive(Debug, Clone, Default)]
pub struct BatteryState {
    pub left: ComponentState,
    pub right: ComponentState,
    pub case: ComponentState,
    pub headset: ComponentState,
    pub primary: BatteryComponent,
    pub secondary: BatteryComponent,
}

impl BatteryState {
    /// Parse an AACP battery status packet.
    ///
    /// Packet format: header(6) + battery_count(1) + [type(1) + 0x01 + level(1) + status(1) + 0x01] * count
    pub fn parse_packet(&mut self, data: &[u8]) -> bool {
        if !data.starts_with(BATTERY_STATUS_HEADER) || data.len() < 7 {
            return false;
        }

        let battery_count = data[6] as usize;
        if battery_count > 3 || data.len() != 7 + 5 * battery_count {
            return false;
        }

        let mut pods_in_packet: Vec<BatteryComponent> = Vec::with_capacity(2);

        for i in 0..battery_count {
            let offset = 7 + 5 * i;
            let comp_type = data[offset];
            let spacer = data[offset + 1];
            let level = data[offset + 2];
            let status_byte = data[offset + 3];
            let end = data[offset + 4];

            if spacer != 0x01 || end != 0x01 {
                return false;
            }

            let comp = match comp_type {
                0x01 => BatteryComponent::Headset,
                0x02 => BatteryComponent::Right,
                0x04 => BatteryComponent::Left,
                0x08 => BatteryComponent::Case,
                _ => continue,
            };

            let bs = match status_byte {
                0x01 => BatteryStatus::Charging,
                0x02 => BatteryStatus::Discharging,
                0x04 => BatteryStatus::Disconnected,
                _ => BatteryStatus::Disconnected,
            };

            let state = ComponentState { level, status: bs };
            match comp {
                BatteryComponent::Headset => self.headset = state,
                BatteryComponent::Right => self.right = state,
                BatteryComponent::Left => self.left = state,
                BatteryComponent::Case => self.case = state,
            }

            if matches!(comp, BatteryComponent::Left | BatteryComponent::Right | BatteryComponent::Headset) {
                pods_in_packet.push(comp);
            }
        }

        // Set primary/secondary based on order in packet
        if let Some(&first) = pods_in_packet.first() {
            if first == BatteryComponent::Headset && pods_in_packet.len() == 1 {
                self.primary = BatteryComponent::Headset;
            } else if self.primary != first {
                self.primary = first;
            }
        }
        if pods_in_packet.len() >= 2 {
            self.secondary = pods_in_packet[1];
        }

        true
    }

    /// Parse an encrypted battery packet (16 bytes from BLE proximity broadcast).
    ///
    /// `is_left_primary` — whether the left pod is the primary pod.
    /// `pod_in_case` — whether the pod is in the case.
    /// `is_headset` — whether this is AirPods Max (single battery).
    pub fn parse_encrypted(&mut self, data: &[u8], is_left_primary: bool, pod_in_case: bool, is_headset: bool) -> bool {
        if data.len() != 16 {
            return false;
        }

        let left_idx = if is_left_primary { 1 } else { 2 };
        let right_idx = if is_left_primary { 2 } else { 1 };
        let case_idx = 3;

        let raw_left = data[left_idx];
        let raw_right = data[right_idx];
        let raw_case = data[case_idx];

        let (left_charging, left_level) = format_battery(raw_left);
        let (right_charging, right_level) = format_battery(raw_right);
        let (case_charging, case_level) = format_battery(raw_case);

        if is_headset {
            let batteries = [left_level, right_level, case_level];
            let statuses = [left_charging, right_charging, case_charging];
            if let Some((idx, &batt)) = batteries.iter().enumerate().find(|(_, &b)| b != 127) {
                self.headset = ComponentState {
                    level: batt,
                    status: if statuses[idx] { BatteryStatus::Charging } else { BatteryStatus::Discharging },
                };
                self.primary = BatteryComponent::Headset;
            }
        } else {
            if left_level != 127 {
                self.left = ComponentState {
                    level: left_level,
                    status: if left_charging { BatteryStatus::Charging } else { BatteryStatus::Discharging },
                };
            }
            if right_level != 127 {
                self.right = ComponentState {
                    level: right_level,
                    status: if right_charging { BatteryStatus::Charging } else { BatteryStatus::Discharging },
                };
            }
            if pod_in_case && case_level != 127 {
                self.case = ComponentState {
                    level: case_level,
                    status: if case_charging { BatteryStatus::Charging } else { BatteryStatus::Discharging },
                };
            }
            self.primary = if is_left_primary { BatteryComponent::Left } else { BatteryComponent::Right };
            self.secondary = if is_left_primary { BatteryComponent::Right } else { BatteryComponent::Left };
        }

        true
    }

    pub fn summary(&self) -> String {
        let fmt = |s: &ComponentState| -> String {
            if s.status == BatteryStatus::Disconnected {
                "—".to_string()
            } else {
                let charge = if s.status == BatteryStatus::Charging { "+" } else { "" };
                format!("{}%{}", s.level, charge)
            }
        };
        if self.primary == BatteryComponent::Headset {
            format!("Headset: {}", fmt(&self.headset))
        } else {
            format!("L {} R {} Case {}", fmt(&self.left), fmt(&self.right), fmt(&self.case))
        }
    }
}

/// Format a single battery byte: bit 7 = charging, bits 0-6 = level (0-127, 127 = unknown).
fn format_battery(byte: u8) -> (bool, u8) {
    let charging = (byte & 0x80) != 0;
    let level = byte & 0x7F;
    (charging, level)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_packet() {
        let mut pkt = BATTERY_STATUS_HEADER.to_vec();
        pkt.push(2); // battery count
        // Left pod: type=0x04, spacer=0x01, level=85, status=0x01(charging), end=0x01
        pkt.extend_from_slice(&[0x04, 0x01, 85, 0x01, 0x01]);
        // Right pod: type=0x02, spacer=0x01, level=70, status=0x02(discharging), end=0x01
        pkt.extend_from_slice(&[0x02, 0x01, 70, 0x02, 0x01]);

        let mut state = BatteryState::default();
        assert!(state.parse_packet(&pkt));
        assert_eq!(state.left.level, 85);
        assert_eq!(state.left.status, BatteryStatus::Charging);
        assert_eq!(state.right.level, 70);
        assert_eq!(state.right.status, BatteryStatus::Discharging);
    }

    #[test]
    fn test_parse_encrypted() {
        // Build a 16-byte encrypted packet
        // Byte 0: reserved
        // Byte 1: left (bit7=charging | level)
        // Byte 2: right
        // Byte 3: case
        let left = 0x80 | 90; // charging, 90%
        let right = 0x00 | 60; // not charging, 60%
        let case = 0x80 | 50; // charging, 50%
        let mut data = vec![0u8; 16];
        data[1] = left;
        data[2] = right;
        data[3] = case;

        let mut state = BatteryState::default();
        assert!(state.parse_encrypted(&data, true, true, false));
        assert_eq!(state.left.level, 90);
        assert_eq!(state.left.status, BatteryStatus::Charging);
        assert_eq!(state.right.level, 60);
        assert_eq!(state.right.status, BatteryStatus::Discharging);
        assert_eq!(state.case.level, 50);
        assert_eq!(state.case.status, BatteryStatus::Charging);
    }

    #[test]
    fn test_summary() {
        let mut state = BatteryState::default();
        state.left = ComponentState { level: 80, status: BatteryStatus::Charging };
        state.right = ComponentState { level: 60, status: BatteryStatus::Discharging };
        state.case = ComponentState { level: 45, status: BatteryStatus::Discharging };
        let s = state.summary();
        assert!(s.contains("80%+"));
        assert!(s.contains("60%"));
        assert!(s.contains("45%"));
    }
}
