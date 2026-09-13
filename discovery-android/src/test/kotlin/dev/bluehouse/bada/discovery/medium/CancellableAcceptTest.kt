/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.discovery.medium

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Pure-JVM coverage for [acceptCancellable] (#288). Real time on purpose:
 * the whole point is that a `withTimeoutOrNull` around a listener with no
 * peer returns when the clock says so, which a virtual-time scheduler
 * would not exercise.
 */
class CancellableAcceptTest {
    @Test
    fun `accept hands back the connected peer`() =
        runBlocking {
            withTimeout(WALLCLOCK_TIMEOUT_MS) {
                val listener = ServerSocket(0, 0, InetAddress.getLoopbackAddress())
                try {
                    val client =
                        async(Dispatchers.IO) {
                            Socket(InetAddress.getLoopbackAddress(), listener.localPort)
                        }
                    val accepted = listener.acceptCancellable()
                    try {
                        assertThat(accepted.isConnected).isTrue()
                        assertThat(accepted.port).isEqualTo(client.await().localPort)
                    } finally {
                        runCatching { accepted.close() }
                        runCatching { client.await().close() }
                    }
                } finally {
                    runCatching { listener.close() }
                }
            }
        }

    @Test
    fun `timeout with no peer returns promptly and closes the listener`() =
        runBlocking {
            withTimeout(WALLCLOCK_TIMEOUT_MS) {
                val listener = ServerSocket(0, 0, InetAddress.getLoopbackAddress())
                try {
                    val startedAt = System.currentTimeMillis()
                    val accepted = withTimeoutOrNull(ACCEPT_TIMEOUT_MS) { listener.acceptCancellable() }
                    val elapsedMillis = System.currentTimeMillis() - startedAt

                    assertThat(accepted).isNull()
                    // A plain blocking accept() would sit here until the
                    // wall-clock guard above trips; the cancellable variant
                    // must come back around the requested timeout.
                    assertThat(elapsedMillis).isLessThan(WALLCLOCK_TIMEOUT_MS)
                    assertThat(listener.isClosed).isTrue()
                } finally {
                    runCatching { listener.close() }
                }
            }
        }

    private companion object {
        const val ACCEPT_TIMEOUT_MS = 200L
        const val WALLCLOCK_TIMEOUT_MS = 5_000L
    }
}
