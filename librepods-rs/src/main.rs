//! librepods-rs — cross-platform AirPods status reader for macOS and Windows.
//!
//! This is the desktop companion to the Wear OS port. On platforms where
//! Apple's private AACP protocol is unavailable we rely solely on the unencrypted
//! BLE proximity-pairing broadcast, which already carries battery level, charging
//! state, in-ear detection and lid state.

mod airpods;
mod ble;
mod tray;

use airpods::AirPodsStatus;
use clap::Parser;
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};

#[derive(Parser)]
#[command(name = "librepods-rs", about = "Cross-platform AirPods status reader (macOS / Windows)")]
struct Args {
    /// Exit after the first parsed advertisement (useful for scripts/tests).
    #[arg(short, long)]
    once: bool,

    /// Run as a system tray app (Windows / macOS) instead of printing to stdout.
    #[arg(long)]
    tray: bool,
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("info")).init();
    let args = Args::parse();

    // Track last-seen state per address so we only print on change.
    let last: Arc<Mutex<HashMap<String, AirPodsStatus>>> = Arc::new(Mutex::new(HashMap::new()));

    let callback = {
        let last = last.clone();
        Arc::new(move |status: AirPodsStatus| {
            let changed = {
                let guard = last.lock().unwrap();
                match guard.get(&status.address) {
                    Some(prev) => status != *prev,
                    None => true,
                }
            };
            if changed {
                let mut guard = last.lock().unwrap();
                guard.insert(status.address.clone(), status.clone());
                let ts = SystemTime::now()
                    .duration_since(UNIX_EPOCH)
                    .map(|d| d.as_secs())
                    .unwrap_or(0);
                println!("[{ts}] {}", status.summary());
            }
        }) as ble::StatusCallback
    };

    log::info!("librepods-rs starting (model protocol: BLE proximity broadcast only)");

    if args.tray {
        return run_tray().await;
    }

    if args.once {
        // Run a bounded scan: stop after the first parsed device or 20s.
        let handle = tokio::spawn(ble::scan_forever(callback));
        tokio::time::sleep(std::time::Duration::from_secs(20)).await;
        handle.abort();
    } else {
        // Loop forever, restarting the scanner if it ever returns.
        loop {
            if let Err(e) = ble::scan_forever(callback.clone()).await {
                log::error!("Scan stopped: {e}; restarting in 3s...");
                tokio::time::sleep(std::time::Duration::from_secs(3)).await;
            }
        }
    }

    Ok(())
}

/// Tray-app entry point: scanner runs on a worker thread and updates the icon
/// tooltip; the main thread pumps the platform event loop so the menu works.
async fn run_tray() -> anyhow::Result<()> {
    let tray = tray::build("LibrePods — scanning Bluetooth…")?;
    log::info!("Tray icon created");

    // The event loop carries `String` user events (the live battery summary).
    let event_loop = winit::event_loop::EventLoop::<String>::with_user_event().build()?;
    let proxy = event_loop.create_proxy();

    // Worker thread: BLE scan -> push summary to the main thread via the proxy.
    std::thread::spawn(move || {
        let rt = match tokio::runtime::Runtime::new() {
            Ok(rt) => rt,
            Err(e) => {
                log::error!("tokio runtime: {e}");
                return;
            }
        };
        let _ = rt.block_on(ble::scan_forever(Arc::new(move |status| {
            let _ = proxy.send_event(status.summary());
        })));
    });

    // Menu events (Quit) on their own thread.
    std::thread::spawn(tray::menu_event_loop);

    // Main thread owns the tray and pumps the platform run loop (required for
    // the tray menu on both Windows and macOS). Tooltip updates arrive as
    // user events so we never touch `TrayIcon` from another thread (it is not
    // `Send`).
    struct TrayApp {
        tray: Arc<tray_icon::TrayIcon>,
    }
    impl winit::application::ApplicationHandler<String> for TrayApp {
        fn resumed(&mut self, _event_loop: &winit::event_loop::ActiveEventLoop) {}
        fn window_event(
            &mut self,
            _event_loop: &winit::event_loop::ActiveEventLoop,
            _window_id: winit::window::WindowId,
            _event: winit::event::WindowEvent,
        ) {
        }
        fn user_event(&mut self, _event_loop: &winit::event_loop::ActiveEventLoop, event: String) {
            if let Err(e) = self.tray.set_tooltip(Some(&event)) {
                log::warn!("set_tooltip: {e}");
            }
        }
    }

    let mut app = TrayApp { tray };
    event_loop.run_app(&mut app)?;
    Ok(())
}
