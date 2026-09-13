/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.send

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the #290 contract: the Bluetooth-off banner follows the adapter
 * state alone, so a picker that already lists a same-Wi-Fi peer still
 * tells the user why BLE-dependent devices are missing.
 */
class SendRadioBannerTest {
    @Test
    fun `bluetooth off shows the banner`() {
        assertTrue(SendRadioBanner.shouldShowBluetoothOff(bluetoothEnabled = false))
    }

    @Test
    fun `bluetooth on hides the banner`() {
        assertFalse(SendRadioBanner.shouldShowBluetoothOff(bluetoothEnabled = true))
    }
}
