/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.medium

/**
 * What this device's Wi-Fi radio can do, as advertised to the peer in
 * `MediumMetadata` (opening `ConnectionRequest`, `UPGRADE_PATH_REQUEST`)
 * and as the `sta_frequency` hint on `SAFE_TO_CLOSE_PRIOR_CHANNEL`.
 *
 * A stock Nearby Connections group owner reads these fields to pick the
 * band and channel of the Wi-Fi Direct group it forms for us. Advertising
 * nothing (the historical default) reads as "2.4 GHz only", so a Samsung
 * receiver forming the group for a Bada sender pinned it to 2.4 GHz and
 * the transfer crawled at a tenth of the speed the reverse direction gets
 * (#287). Pure data so `:core-protocol` stays free of `android.*`; the
 * Android layer fills it from `WifiManager`.
 *
 * @property supports5Ghz Whether the radio can operate on 5 GHz.
 * @property supports6Ghz Whether the radio can operate on 6 GHz.
 * @property staFrequencyMhz Frequency of the current infrastructure (STA)
 *   association in MHz, or `null` when not connected / unknown. Lets the
 *   group owner co-locate the P2P group with our AP channel and avoid
 *   multi-channel concurrency.
 */
public data class LocalWifiCapabilities(
    val supports5Ghz: Boolean = false,
    val supports6Ghz: Boolean = false,
    val staFrequencyMhz: Int? = null,
) {
    /** `sta_frequency` / `ap_frequency` wire value: the frequency, or -1 when unknown. */
    public val frequencyOrNotSet: Int
        get() = staFrequencyMhz?.takeIf { it > 0 } ?: FREQUENCY_NOT_SET

    public companion object {
        /** Wire sentinel for "no frequency hint" (proto default for `ap_frequency`). */
        public const val FREQUENCY_NOT_SET: Int = -1

        /** Nothing known: the pre-#287 wire shape (no band support, no frequency hint). */
        public val Unknown: LocalWifiCapabilities = LocalWifiCapabilities()
    }
}
