//! System tray frontend for `librepods-rs`.
//!
//! Builds a status-bar / notification-area icon whose tooltip shows the live
//! AirPods battery summary. Menu allows toggling noise control mode,
//! conversational awareness, and other AACP commands.
//!
//! Note: `TrayIcon` is NOT Send/Sync on Windows (uses Rc internally), so all
//! tray interactions must happen on the main thread.

use tray_icon::{
    menu::{CheckMenuItem, Menu, MenuEvent, MenuItem, PredefinedMenuItem, Submenu},
    Icon, TrayIcon, TrayIconBuilder,
};

pub const QUIT_ID: &str = "quit";
pub const NOISE_OFF_ID: &str = "noise_off";
pub const NOISE_ANC_ID: &str = "noise_anc";
pub const NOISE_TRANSPARENCY_ID: &str = "noise_transparency";
pub const NOISE_ADAPTIVE_ID: &str = "noise_adaptive";
pub const CA_TOGGLE_ID: &str = "ca_toggle";

use crate::commands::NoiseControlMode;

/// Shared state communicated to the tray menu.
#[derive(Debug, Clone)]
#[allow(dead_code)]
pub struct TrayState {
    pub noise_mode: NoiseControlMode,
    pub ca_enabled: bool,
    pub battery_summary: String,
}

impl Default for TrayState {
    fn default() -> Self {
        Self {
            noise_mode: NoiseControlMode::Adaptive,
            ca_enabled: false,
            battery_summary: "Scanning…".to_string(),
        }
    }
}

/// Event emitted when the user interacts with the tray menu.
#[derive(Debug, Clone)]
pub enum TrayEvent {
    NoiseControl(NoiseControlMode),
    ConversationalAwarenessToggle,
    Quit,
}

pub struct TrayHandle {
    pub tray: TrayIcon,
    pub noise_off: CheckMenuItem,
    pub noise_anc: CheckMenuItem,
    pub noise_transparency: CheckMenuItem,
    pub noise_adaptive: CheckMenuItem,
    pub ca_toggle: CheckMenuItem,
}

/// Build the tray icon with a full menu.
pub fn build(initial_tooltip: &str, initial_state: &TrayState) -> anyhow::Result<TrayHandle> {
    #[cfg(target_os = "macos")]
    tray_icon::set_notification_activation_policy(tray_icon::NotificationActivationPolicy::Accessory);

    let icon = make_icon()?;

    let menu = Menu::new();

    let noise_menu = Submenu::new("Noise Control", true);
    let noise_off = CheckMenuItem::with_id(NOISE_OFF_ID, "Off", true, initial_state.noise_mode == NoiseControlMode::Off, None);
    let noise_anc = CheckMenuItem::with_id(NOISE_ANC_ID, "Noise Cancellation", true, initial_state.noise_mode == NoiseControlMode::NoiseCancellation, None);
    let noise_transparency = CheckMenuItem::with_id(NOISE_TRANSPARENCY_ID, "Transparency", true, initial_state.noise_mode == NoiseControlMode::Transparency, None);
    let noise_adaptive = CheckMenuItem::with_id(NOISE_ADAPTIVE_ID, "Adaptive", true, initial_state.noise_mode == NoiseControlMode::Adaptive, None);

    noise_menu.append(&noise_off)?;
    noise_menu.append(&noise_anc)?;
    noise_menu.append(&noise_transparency)?;
    noise_menu.append(&noise_adaptive)?;
    menu.append(&noise_menu)?;

    menu.append(&PredefinedMenuItem::separator())?;

    let ca_toggle = CheckMenuItem::with_id(
        CA_TOGGLE_ID,
        "Conversational Awareness",
        true,
        initial_state.ca_enabled,
        None,
    );
    menu.append(&ca_toggle)?;

    menu.append(&PredefinedMenuItem::separator())?;

    let quit = MenuItem::with_id(QUIT_ID, "Quit LibrePods", true, None);
    menu.append(&quit)?;

    let tray = TrayIconBuilder::new()
        .with_tooltip(initial_tooltip)
        .with_icon(icon)
        .with_menu(Box::new(menu))
        .build()?;

    Ok(TrayHandle {
        tray,
        noise_off,
        noise_anc,
        noise_transparency,
        noise_adaptive,
        ca_toggle,
    })
}

/// Update the checked state of noise control menu items.
pub fn update_noise_state(handle: &TrayHandle, mode: NoiseControlMode) {
    handle.noise_off.set_checked(mode == NoiseControlMode::Off);
    handle.noise_anc.set_checked(mode == NoiseControlMode::NoiseCancellation);
    handle.noise_transparency.set_checked(mode == NoiseControlMode::Transparency);
    handle.noise_adaptive.set_checked(mode == NoiseControlMode::Adaptive);
}

/// Update the CA toggle state.
#[allow(dead_code)]
pub fn update_ca_state(handle: &TrayHandle, enabled: bool) {
    handle.ca_toggle.set_checked(enabled);
}

/// Poll menu events and return them. Call this from the main thread event loop.
pub fn poll_menu_event() -> Option<TrayEvent> {
    let event = MenuEvent::receiver().try_recv().ok()?;
    Some(match event.id.as_ref() {
        QUIT_ID => TrayEvent::Quit,
        NOISE_OFF_ID => TrayEvent::NoiseControl(NoiseControlMode::Off),
        NOISE_ANC_ID => TrayEvent::NoiseControl(NoiseControlMode::NoiseCancellation),
        NOISE_TRANSPARENCY_ID => TrayEvent::NoiseControl(NoiseControlMode::Transparency),
        NOISE_ADAPTIVE_ID => TrayEvent::NoiseControl(NoiseControlMode::Adaptive),
        CA_TOGGLE_ID => TrayEvent::ConversationalAwarenessToggle,
        _ => return None,
    })
}

/// A 32x32 solid LibrePods-blue icon encoded as raw RGBA.
fn make_icon() -> anyhow::Result<Icon> {
    let size = 32u32;
    let mut rgba = vec![0u8; (size * size * 4) as usize];
    for px in rgba.chunks_exact_mut(4) {
        px[0] = 30;
        px[1] = 144;
        px[2] = 255;
        px[3] = 255;
    }
    Ok(Icon::from_rgba(rgba, size, size)?)
}
