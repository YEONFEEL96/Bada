/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.service.receiver

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ReceiverMasterSwitchTest {
    @Test
    fun `defaults to on so existing installs keep receiving`() {
        assertThat(ReceiverMasterSwitch(FakeSharedPreferences()).isEnabled()).isTrue()
    }

    @Test
    fun `off persists and reads back`() {
        val prefs = FakeSharedPreferences()
        ReceiverMasterSwitch(prefs).setEnabled(false)
        assertThat(ReceiverMasterSwitch(prefs).isEnabled()).isFalse()
        ReceiverMasterSwitch(prefs).setEnabled(true)
        assertThat(ReceiverMasterSwitch(prefs).isEnabled()).isTrue()
    }
}
