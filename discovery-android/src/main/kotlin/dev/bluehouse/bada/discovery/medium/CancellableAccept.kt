/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.discovery.medium

import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * `ServerSocket.accept()` that actually honours coroutine cancellation.
 *
 * A plain `withContext(Dispatchers.IO) { accept() }` parks an IO thread in
 * a syscall that neither coroutine cancellation nor `Thread.interrupt`
 * can wake, so a `withTimeoutOrNull` wrapped around it never returns
 * until a peer connects. That is exactly how the receiver-side Wi-Fi
 * Direct / hotspot upgrade hung forever on a Vivo X200 Pro when the
 * sender ignored the offer (#288): the 30 s orchestrator timeout fired
 * on paper, but the accept could not be interrupted and the whole
 * inbound connection stayed parked behind it.
 *
 * Closing the listener is the only reliable wake-up, so on cancellation
 * this helper closes [this] and lets the dedicated accept thread fail out
 * of the syscall on its own. Callers must not rely on the listener
 * surviving a cancelled accept. A peer that races the cancellation and
 * gets accepted anyway is closed rather than leaked.
 */
internal suspend fun ServerSocket.acceptCancellable(): Socket =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { runCatching { close() } }
        thread(name = "bada-accept-$localPort", isDaemon = true) {
            val accepted =
                try {
                    accept()
                } catch (e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                    return@thread
                }
            if (continuation.isActive) {
                continuation.resume(accepted)
            } else {
                accepted.runCatching { close() }
            }
        }
    }
