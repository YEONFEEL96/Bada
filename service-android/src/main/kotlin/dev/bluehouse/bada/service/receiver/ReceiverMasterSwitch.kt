/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.service.receiver

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences

/**
 * Master on/off for Bada's receive side (#239).
 *
 * Off means the receiver foreground service does not run: no mDNS
 * publish, no BLE advertisement, no TCP listener, so the device is not
 * discoverable and nothing is received until the user turns it back on.
 * Every path that starts [ReceiverForegroundService] (launcher, settings
 * identity refresh, Quick Settings tile, NFC wake) checks this first, and
 * the service itself refuses to come up while it is off so a stray
 * `startForegroundService` cannot resurrect it. Sending is unaffected.
 *
 * Default is on, matching the behaviour before the switch existed.
 */
public class ReceiverMasterSwitch(
    private val prefs: SharedPreferences,
) {
    public fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    /** Synchronous on purpose: a rare user action whose loss (process killed right after) would be confusing. */
    @SuppressLint("ApplySharedPref")
    public fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).commit()
    }

    public companion object {
        private const val PREFS_NAME = "bada.receiver_master"
        private const val KEY_ENABLED = "enabled"

        public fun from(context: Context): ReceiverMasterSwitch =
            ReceiverMasterSwitch(
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            )

        /** Convenience for the start-path guards. */
        public fun isEnabled(context: Context): Boolean = from(context).isEnabled()
    }
}
