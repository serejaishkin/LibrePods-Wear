//! Cross-platform BLE scanner built on [`btleplug`].
//!
//! `btleplug` provides a single async API that works on Linux (BlueZ),
//! Windows (WinRT), and macOS (CoreBluetooth). We only need passive scanning
//! for Apple manufacturer data; no pairing or GATT is required.

use crate::airpods::{parse_advertisement, AirPodsStatus, APPLE_MANUFACTURER_ID};
use anyhow::Result;
use btleplug::api::{Central, CentralEvent, Manager as _, ScanFilter};
use btleplug::platform::{Adapter, Manager};
use futures::StreamExt;
use std::sync::Arc;
use std::time::Duration;

/// Callback invoked for every parsed AirPods advertisement.
pub type StatusCallback = Arc<dyn Fn(AirPodsStatus) + Send + Sync>;

/// Discover a usable Bluetooth adapter, preferring the first powered one.
async fn pick_adapter() -> Result<Adapter> {
    let manager = Manager::new().await?;
    let adapters = manager.adapters().await?;
    if adapters.is_empty() {
        anyhow::bail!("No Bluetooth adapters found");
    }
    Ok(adapters.into_iter().next().unwrap())
}

/// Scan continuously, calling `on_status` for every AirPods advertisement seen.
///
/// This blocks until the scan errors out or the process is terminated. Parsing
/// happens on every manufacturer-data update; callers typically dedupe by
/// address and only refresh UI when values change.
pub async fn scan_forever(on_status: StatusCallback) -> Result<()> {
    let adapter = pick_adapter().await?;
    log::info!("Using Bluetooth adapter for scanning");

    adapter.start_scan(ScanFilter::default()).await?;
    log::info!("BLE scan started; listening for Apple 0x004C advertisements...");

    let mut events = adapter.events().await?;
    while let Some(event) = events.next().await {
        let (id, manufacturer_data) = match &event {
            CentralEvent::ManufacturerDataAdvertisement { id, manufacturer_data } => {
                (id.clone(), manufacturer_data.clone())
            }
            _ => continue,
        };

        let address = id.to_string();
        if let Some(data) = manufacturer_data.get(&APPLE_MANUFACTURER_ID) {
            if let Some(status) = parse_advertisement("", &address, data) {
                on_status(status);
            }
        }
    }

    // If the stream ended, try to restart after a short delay.
    let _ = adapter.stop_scan().await;
    tokio::time::sleep(Duration::from_secs(2)).await;
    Ok(())
}
