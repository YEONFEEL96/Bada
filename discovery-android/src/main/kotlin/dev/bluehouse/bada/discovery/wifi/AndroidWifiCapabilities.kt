/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.discovery.wifi

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import dev.bluehouse.bada.protocol.medium.LocalWifiCapabilities

/**
 * Reads the local Wi-Fi radio's band support and current STA frequency
 * into the pure [LocalWifiCapabilities] the sender advertises (#287).
 *
 * Band support comes from `WifiManager.is5GHzBandSupported` /
 * `is6GHzBandSupported` (the latter API 30+). The STA frequency comes
 * from the deprecated `connectionInfo`, which still reports `frequency`
 * without a location grant on every API level we ship to; SSID / BSSID
 * would be redacted there, but we never read them. Every failure degrades
 * to [LocalWifiCapabilities.Unknown], the pre-#287 wire shape, so a
 * misbehaving OEM Wi-Fi stack can never block a send.
 */
public object AndroidWifiCapabilities {
    @Suppress("DEPRECATION", "TooGenericExceptionCaught", "SwallowedException")
    public fun read(context: Context): LocalWifiCapabilities {
        val wifi =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return LocalWifiCapabilities.Unknown
        return try {
            LocalWifiCapabilities(
                supports5Ghz = wifi.is5GHzBandSupported,
                supports6Ghz = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wifi.is6GHzBandSupported,
                staFrequencyMhz = wifi.connectionInfo?.frequency?.takeIf { it > 0 },
            )
        } catch (t: Throwable) {
            LocalWifiCapabilities.Unknown
        }
    }
}
