package com.dlna.dlnacaster

import org.fourthline.cling.model.meta.Device

data class DeviceDisplay(val device: Device<*, *, *>) {
    override fun toString(): String {
        return device.details?.friendlyName ?: device.details?.modelDetails?.modelName ?: "Unknown Device"
    }
}