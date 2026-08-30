//! System tray frontend for `librepods-rs`.
//!
//! Builds a status-bar / notification-area icon whose tooltip shows the live
//! AirPods battery summary. The icon is drawn programmatically (no bundled
//! asset). The actual Bluetooth scanning runs on a separate thread and pushes
//! updates here via [`TrayIcon::set_tooltip`].

use std::sync::Arc;

use tray_icon::{
    menu::{Menu, MenuEvent, MenuId, MenuItem},
    Icon, TrayIcon, TrayIconBuilder,
};

/// Menu item id used for the Quit entry.
pub const QUIT_ID: &str = "quit";

/// Build the tray icon with a minimal menu (Quit).
pub fn build(initial_tooltip: &str) -> anyhow::Result<Arc<TrayIcon>> {
    // On macOS, show only in the status bar (no dock icon) when possible.
    #[cfg(target_os = "macos")]
    tray_icon::set_notification_activation_policy(tray_icon::NotificationActivationPolicy::Accessory);

    let icon = make_icon()?;
    let menu = Menu::new();
    let quit = MenuItem::with_id(MenuId::new(QUIT_ID), "Quit LibrePods", true, None);
    menu.append(&quit)?;

    let tray = TrayIconBuilder::new()
        .with_tooltip(initial_tooltip)
        .with_icon(icon)
        .with_menu(Box::new(menu))
        .build()?;
    Ok(Arc::new(tray))
}

/// Block the current thread processing menu events. Quitting terminates the
/// process; the caller is expected to have started the BLE scanner elsewhere.
pub fn menu_event_loop() {
    for event in MenuEvent::receiver() {
        if event.id == MenuId::new(QUIT_ID) {
            std::process::exit(0);
        }
    }
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
