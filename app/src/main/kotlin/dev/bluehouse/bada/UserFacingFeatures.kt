/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada

/**
 * Compile-time switches for user-facing surfaces that exist in the code
 * but are not ready to be exposed. Mirrors `UserFacingMediumFeatures` in
 * `:discovery-android`, which gates transport routes the same way.
 */
internal object UserFacingFeatures {
    /**
     * The tap-to-share Name Card flow (setup card in Settings, NFC / BLE
     * exchange). The pipeline is not usable end to end yet, so the
     * Settings entry point stays hidden; the activities and services
     * remain in the manifest for the in-progress work.
     */
    const val NAME_CARD_ENABLED: Boolean = false
}
