//! librepods-rs — cross-platform AirPods status reader for macOS and Windows.
//!
//! This is the desktop companion to the Wear OS port. On platforms where
//! Apple's private AACP protocol is unavailable we rely solely on the unencrypted
//! BLE proximity-pairing broadcast, which already carries battery level, charging
//! state, in-ear detection and lid state.

#[allow(dead_code)]
mod aacp;
mod airpods;
mod ble;
#[allow(dead_code)]
mod battery_parser;
#[allow(dead_code)]
mod commands;
#[allow(dead_code)]
mod ear_detection;
mod tray;

use airpods::AirPodsStatus;
use clap::Parser;
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};
#[allow(unused_imports)]
use commands::NoiseControlMode;
#[allow(unused_imports)]
use battery_parser::BatteryState;

#[derive(Parser)]
#[command(name = "librepods-rs", about = "Cross-platform AirPods status reader (macOS / Windows)")]
struct Args {
    /// Exit after the first parsed advertisement (useful for scripts/tests).
    #[arg(short, long)]
    once: bool,

    /// Run as a system tray app (Windows / macOS) instead of printing to stdout.
    #[arg(long)]
    tray: bool,

    /// Set noise control mode (requires AACP connection, Linux only).
    /// Values: off, anc, transparency, adaptive
    #[arg(long)]
    noise: Option<String>,

    /// Toggle conversational awareness on or off (requires AACP, Linux only).
    #[arg(long)]
    ca: Option<bool>,

    /// Rename AirPods (requires AACP, Linux only, max 32 chars).
    #[arg(long)]
    rename: Option<String>,
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("info")).init();
    let args = Args::parse();

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

    log::info!("librepods-rs starting");
    log::info!("AACP commands (noise, ca, rename) require Linux with BlueZ L2CAP support.");

    if args.tray {
        return run_tray().await;
    }

    if args.once {
        let handle = tokio::spawn(ble::scan_forever(callback));
        tokio::time::sleep(std::time::Duration::from_secs(20)).await;
        handle.abort();
    } else {
        loop {
            if let Err(e) = ble::scan_forever(callback.clone()).await {
                log::error!("Scan stopped: {e}; restarting in 3s...");
                tokio::time::sleep(std::time::Duration::from_secs(3)).await;
            }
        }
    }

    Ok(())
}

/// Tray-app entry point. TrayIcon is NOT Send on Windows (Rc-based), so all
/// tray interactions happen on the main thread. BLE scanner runs on a worker thread
/// and pushes summary strings via winit's user event proxy.
async fn run_tray() -> anyhow::Result<()> {
    let initial_state = tray::TrayState::default();
    let handle = tray::build("LibrePods — scanning Bluetooth…", &initial_state)?;
    log::info!("Tray icon created with noise control + CA menu");

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

    // Main thread owns the tray and pumps the platform run loop.
    // Menu events are polled on each user event (timer-based workaround).
    struct TrayApp {
        tray: tray::TrayHandle,
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
            if let Err(e) = self.tray.tray.set_tooltip(Some(&event)) {
                log::warn!("set_tooltip: {e}");
            }
            // Poll menu events after each user event (tray menu interactions).
            self.poll_menu_events();
        }

        fn about_to_wait(&mut self, _event_loop: &winit::event_loop::ActiveEventLoop) {
            // Also poll periodically (handles clicks when no BLE data arrives).
            self.poll_menu_events();
        }
    }

    impl TrayApp {
        fn poll_menu_events(&mut self) {
            while let Some(event) = tray::poll_menu_event() {
                match event {
                    tray::TrayEvent::NoiseControl(mode) => {
                        log::info!("Noise control -> {:?}", mode);
                        tray::update_noise_state(&self.tray, mode);
                    }
                    tray::TrayEvent::ConversationalAwarenessToggle => {
                        let new_state = !self.tray.ca_toggle.is_checked();
                        log::info!("Conversational Awareness -> {}", new_state);
                        self.tray.ca_toggle.set_checked(new_state);
                    }
                    tray::TrayEvent::Quit => {
                        std::process::exit(0);
                    }
                }
            }
        }
    }

    let mut app = TrayApp { tray: handle };
    event_loop.run_app(&mut app)?;
    Ok(())
}
