#[cfg(not(target_os = "android"))]
use std::{
    collections::HashMap,
    hash::{Hash, Hasher},
};

#[cfg(not(target_os = "android"))]
use futures_lite::StreamExt;
#[cfg(not(target_os = "android"))]
use nusb::hotplug::HotplugEvent;
use nusb::{
    descriptors::TransferType,
    io::{EndpointRead, EndpointWrite},
    transfer::{Bulk, Direction},
};
use tracing::{debug, error, info, warn};

use crate::error::RusbmuxError;

use super::{AnyDeviceInfo, BoxStream, Event, UsbBackend};
#[cfg(not(target_os = "android"))]
use super::{APPLE_VID, next_device_id};

pub struct NusbBackend;

#[cfg(not(target_os = "android"))]
impl UsbBackend for NusbBackend {
    #[inline]
    async fn list_devices(&self) -> Vec<AnyDeviceInfo> {
        match nusb::list_devices().await {
            Ok(devices) => devices
                .filter(|dev| {
                    let is_apple = dev.vendor_id() == APPLE_VID;

                    if is_apple {
                        debug!(
                            vid = dev.vendor_id(),
                            pid = dev.product_id(),
                            "Found an Apple device"
                        );
                    }

                    is_apple
                })
                .map(AnyDeviceInfo::Nusb)
                .collect(),
            Err(err) => {
                error!(%err, "nusb list devices failed");
                vec![]
            }
        }
    }

    #[inline]
    async fn watch_devices(&self) -> Result<BoxStream<Result<Event, RusbmuxError>>, RusbmuxError> {
        watch_usb().await
    }
}

#[cfg(target_os = "android")]
impl UsbBackend for NusbBackend {
    async fn list_devices(&self) -> Vec<AnyDeviceInfo> { Vec::new() }
    async fn watch_devices(&self) -> Result<BoxStream<Result<Event, RusbmuxError>>, RusbmuxError> {
        Err(RusbmuxError::InvalidData("Android discovery belongs to UsbManager; supply an opened descriptor"))
    }
}

#[cfg(not(target_os = "android"))]
async fn watch_usb() -> Result<BoxStream<Result<Event, RusbmuxError>>, RusbmuxError> {
    let mut devices_id_map = HashMap::new();
    let mut devices_hotplug = nusb::watch_devices()
        .map_err(|err| {
            error!(%err, "Failed to create a device hotplug");
            RusbmuxError::HotPlugNotSupported
        })?
        .filter_map(|event| {
            // don't include the connected event if it's not an apple devices
            if matches!(&event, HotplugEvent::Connected(dev) if dev.vendor_id() != super::APPLE_VID)
            {
                return None;
            }

            Some(event)
        });

    Ok(Box::pin(async_stream::try_stream! {
        let current_connected_devices = NusbBackend.list_devices().await;

        for device_info in current_connected_devices {
            let id = next_device_id();
            devices_id_map.insert(device_info.opaque_id(), id);

            yield Event::Connected(device_info, id);
        }

        while let Some(device_event) = devices_hotplug.next().await {
            match device_event {
                HotplugEvent::Connected(device_info) => {
                    let info = AnyDeviceInfo::Nusb(device_info);
                    let id = next_device_id();
                    devices_id_map.insert(info.opaque_id(), id);

                    yield Event::Connected(info, id);
                }
                HotplugEvent::Disconnected(device_id) => {
                    if let Some(id) = devices_id_map.remove(&hash_id(device_id)) {
                        yield Event::Disconnected(id)
                    }
                }
            }
        }
    }))
}

pub(crate) async fn device_endpoints(
    dev: &nusb::Device,
) -> Result<(EndpointRead<Bulk>, EndpointWrite<Bulk>), RusbmuxError> {
    let current_cfg = dev
        .active_configuration()
        .map_or(0, |c| c.configuration_value());

    debug!("Current device configuration: {current_cfg}");

    // Nuvio Android probe: prefer the active configuration to avoid needless reconfiguration.
    let mut configurations = dev.configurations().collect::<Vec<_>>();
    configurations.sort_by_key(|cfg| cfg.configuration_value() != current_cfg);
    let (interface_descriptor, intf_cfg_num) = configurations
        .into_iter()
        .map(|cfg| (cfg.configuration_value(), cfg.interface_alt_settings()))
        .find_map(|(cfg_num, intfs)| {
            for intf in intfs {
                if intf.class() == super::APPLE_USBMUX_CLASS
                    && intf.subclass() == super::APPLE_USBMUX_SUBCLASS
                    && intf.protocol() == super::APPLE_USBMUX_PROTOCOL
                {
                    debug!(
                        configuration = cfg_num,
                        interface_number = intf.interface_number(),
                        "Found usbmux interface"
                    );

                    return Some((intf, cfg_num));
                }
            }
            None
        })
        .ok_or(RusbmuxError::UsbmuxInterfaceNotFound)?;

    // zero means it doesn't have any active configuration
    if intf_cfg_num != current_cfg || current_cfg == 0 {
        info!(
            old_cfg = current_cfg,
            new_cfg = intf_cfg_num,
            "Switching device configuration"
        );

        // TODO: maybe don't search for it again
        let cfg = dev
            .configurations()
            .find(|c| c.configuration_value() == intf_cfg_num)
            .unwrap();

        // make sure to detach any interfaces before setting the new configuration
        for intf in cfg.interface_alt_settings() {
            if intf.alternate_setting() != 0 {
                continue;
            }

            if let Err(err) = dev.detach_kernel_driver(intf.interface_number()) {
                warn!(
                    interface = intf.interface_number(),
                    %err,
                    "Failed to detach kernel driver"
                );
            } else {
                debug!(
                    interface = intf.interface_number(),
                    "Detached kernel driver"
                );
            }
        }

        dev.set_configuration(intf_cfg_num).await?;
    }

    let intf = dev
        .claim_interface(interface_descriptor.interface_number())
        .await?;

    let end_out = interface_descriptor
        .endpoints()
        .find(|ep| {
            matches!(ep.direction(), Direction::Out)
                && matches!(ep.transfer_type(), TransferType::Bulk)
        })
        .ok_or(RusbmuxError::BulkOutEndpointNotFound)?
        .address();

    let end_in = interface_descriptor
        .endpoints()
        .find(|ep| {
            matches!(ep.direction(), Direction::In)
                && matches!(ep.transfer_type(), TransferType::Bulk)
        })
        .ok_or(RusbmuxError::BulkInEndpointNotFound)?
        .address();

    debug!(
        interface = interface_descriptor.interface_number(),
        end_in, end_out, "Claimed interface and endpoints"
    );

    let reader: EndpointRead<Bulk> = intf
        .endpoint(end_in)?
        .reader(super::MAX_PACKET_SIZE * 2)
        .with_num_transfers(3);
    let writer: EndpointWrite<Bulk> = intf
        .endpoint(end_out)?
        .writer(super::MAX_PACKET_SIZE)
        .with_num_transfers(3);

    Ok((reader, writer))
}

#[cfg(not(target_os = "android"))]
fn hash_id(id: nusb::DeviceId) -> u64 {
    let mut hasher = std::hash::DefaultHasher::new();
    id.hash(&mut hasher);
    hasher.finish()
}
