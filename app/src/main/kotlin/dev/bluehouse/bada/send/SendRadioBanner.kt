/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.send

/**
 * Pure decision for the send picker's radio banners (#290).
 *
 * Kept separate from [EmptyPeerRadioHint] on purpose: that hint is
 * empty-list guidance, while the Bluetooth-off banner must show even when
 * the picker already lists a same-Wi-Fi peer. Stock Quick Share receivers
 * carry their name only in the BLE fast advertisement and publish mDNS
 * only after seeing a sender's BLE pulse, so with Bluetooth off the user
 * sees a half-populated list (LAN peers only, nameless or missing Samsung
 * devices) and nothing explaining why.
 */
internal object SendRadioBanner {
    /**
     * Whether the Bluetooth-off banner should be visible. Deliberately a
     * function of the adapter state alone: the number of peers already
     * listed is irrelevant to whether BLE-dependent peers are being missed.
     */
    fun shouldShowBluetoothOff(bluetoothEnabled: Boolean): Boolean = !bluetoothEnabled
}
