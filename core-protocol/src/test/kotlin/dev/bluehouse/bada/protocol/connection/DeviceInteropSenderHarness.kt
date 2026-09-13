/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.connection

import com.google.android.gms.nearby.sharing.Protocol
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.BandwidthUpgradeNegotiationFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.ConnectionRequestFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.ConnectionResponseFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OfflineFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.V1Frame
import com.google.protobuf.ByteString
import dev.bluehouse.bada.protocol.crypto.D2DKeyDerivation
import dev.bluehouse.bada.protocol.crypto.D2DRole
import dev.bluehouse.bada.protocol.crypto.securemessage.SecureChannel
import dev.bluehouse.bada.protocol.endpoint.DeviceType
import dev.bluehouse.bada.protocol.endpoint.EndpointInfo
import dev.bluehouse.bada.protocol.medium.Medium
import dev.bluehouse.bada.protocol.payload.PayloadAssembler
import dev.bluehouse.bada.protocol.payload.PayloadEvent
import dev.bluehouse.bada.protocol.payload.PayloadTransferEncoder
import dev.bluehouse.bada.protocol.sharing.IntroductionFrame
import dev.bluehouse.bada.protocol.sharing.OutboundSharingFsm
import dev.bluehouse.bada.protocol.sharing.SharingFrames
import dev.bluehouse.bada.protocol.sharing.SharingFsmEffect
import dev.bluehouse.bada.protocol.sharing.SharingFsmEvent
import dev.bluehouse.bada.protocol.transport.FramedConnection
import dev.bluehouse.bada.protocol.ukey2.Ukey2Client
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.channels.Channels
import java.security.SecureRandom

/**
 * Manual on-device harness for #288: plays a stock-GMS-like sender against
 * a real Bada receiver over TCP and IGNORES its bandwidth-upgrade offer
 * (or declines it), so the receiver must keep negotiating on the LAN
 * channel. Skipped unless `BADA_DEVICE_HOST` is set. Read the receiver's
 * port from its `advertise: registered ... port=` log line; when the LAN
 * isolates the workstation from the phone, `adb forward tcp:<port>
 * tcp:<port>` and point the harness at `127.0.0.1`:
 *
 *     BADA_DEVICE_HOST=127.0.0.1 BADA_DEVICE_PORT=39007 \
 *       ./gradlew :core-protocol:test --tests '*DeviceInteropSenderHarness*' --rerun
 *
 * `BADA_SENDER_MODE=decline` replies UPGRADE_FAILURE to the offer instead
 * of ignoring it. Accept the consent sheet on the phone when it appears;
 * the harness then streams one 300 KB file and waits for the receiver's
 * Disconnection.
 */
class DeviceInteropSenderHarness {
    @Test
    fun `send one file to a real receiver while ignoring its upgrade offer`() {
        val host = System.getenv("BADA_DEVICE_HOST")
        assumeTrue(host != null, "BADA_DEVICE_HOST not set; manual harness skipped")
        val port = System.getenv("BADA_DEVICE_PORT")?.toInt() ?: error("BADA_DEVICE_PORT not set")
        val decline = System.getenv("BADA_SENDER_MODE") == "decline"
        runBlocking {
            withTimeout(TOTAL_TIMEOUT_MS) { Session(host!!, port, decline).run() }
        }
    }

    private class Session(
        private val host: String,
        private val port: Int,
        private val decline: Boolean,
    ) {
        private val startedAt = System.currentTimeMillis()
        private val secureRandom = SecureRandom()
        private val payloadId = secureRandom.nextLong()
        private val fileBytes = ByteArray(FILE_SIZE_BYTES).also { secureRandom.nextBytes(it) }
        private val assembler = PayloadAssembler()
        private val fsm =
            OutboundSharingFsm(
                introduction =
                    IntroductionFrame
                        .newBuilder()
                        .addFileMetadata(
                            Protocol.FileMetadata
                                .newBuilder()
                                .setName(FILE_NAME)
                                .setPayloadId(payloadId)
                                .setId(payloadId)
                                .setSize(fileBytes.size.toLong())
                                .setMimeType("application/octet-stream")
                                .setType(Protocol.FileMetadata.Type.UNKNOWN),
                        ).build(),
                secureRandom = secureRandom,
            )

        private fun log(line: String) = println("[harness +${System.currentTimeMillis() - startedAt}ms] $line")

        suspend fun run() {
            val socket = Socket().apply { connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }
            log("connected to $host:$port")
            val transport = FramedConnection(socket)
            val channel = openSecureChannel(transport)
            applyEffects(channel, fsm.start())
            var done = false
            while (!done) {
                done = handleFrame(channel, channel.receiveOfflineFrame())
            }
            runCatching { channel.close() }
            runCatching { transport.close() }
        }

        private suspend fun openSecureChannel(transport: FramedConnection): SecureChannel {
            transport.sendFrame(connectionRequest().toByteArray())
            log("sent ConnectionRequest mediums=[WIFI_LAN, WIFI_DIRECT]")
            val handshake = Ukey2Client.performHandshake(transport, secureRandom)
            log("UKEY2 client handshake complete")
            val theirResponse = OfflineFrame.parseFrom(transport.receiveFrame())
            log("received ${theirResponse.v1.type}")
            transport.sendFrame(connectionResponse().toByteArray())
            val sessionKeys =
                D2DKeyDerivation.derive(
                    dhs = handshake.dhs,
                    ukeyClientInitMsg = handshake.clientInitMsg,
                    ukeyServerInitMsg = handshake.serverInitMsg,
                    role = D2DRole.CLIENT,
                )
            return SecureChannel(transport, sessionKeys, secureRandom)
        }

        /** Returns true once the session is over. */
        private suspend fun handleFrame(
            channel: SecureChannel,
            frame: OfflineFrame,
        ): Boolean =
            when (frame.v1.type) {
                V1Frame.FrameType.DISCONNECTION -> {
                    log("received DISCONNECTION; done")
                    true
                }
                V1Frame.FrameType.BANDWIDTH_UPGRADE_NEGOTIATION -> {
                    handleUpgradeFrame(channel, frame)
                    false
                }
                V1Frame.FrameType.PAYLOAD_TRANSFER -> handlePayloadFrame(channel, frame)
                else -> {
                    log("received ${frame.v1.type} (ignored)")
                    false
                }
            }

        private suspend fun handleUpgradeFrame(
            channel: SecureChannel,
            frame: OfflineFrame,
        ) {
            val negotiation = frame.v1.bandwidthUpgradeNegotiation
            log("received BANDWIDTH_UPGRADE_NEGOTIATION ${negotiation.eventType}")
            val isOffer = negotiation.eventType == BandwidthUpgradeNegotiationFrame.EventType.UPGRADE_PATH_AVAILABLE
            if (decline && isOffer) {
                val medium = Medium.fromUpgradePathMedium(negotiation.upgradePathInfo.medium) ?: Medium.WIFI_DIRECT
                channel.sendOfflineFrame(BandwidthUpgradeFrames.upgradeFailure(medium))
                log("replied UPGRADE_FAILURE($medium)")
            } else if (isOffer) {
                log("ignoring the offer like a stock sender that never joins the group")
            }
        }

        /** Returns true when the receiver rejected the transfer. */
        private suspend fun handlePayloadFrame(
            channel: SecureChannel,
            frame: OfflineFrame,
        ): Boolean {
            val event = assembler.onPayloadTransfer(frame.v1.payloadTransfer)
            if (event !is PayloadEvent.BytesComplete) return false
            val sharing = SharingFrames.parse(event.data)
            log("received sharing ${sharing.v1.type}")
            val effects = fsm.onEvent(SharingFsmEvent.FrameReceived(sharing))
            applyEffects(channel, effects)
            if (effects.any { it is SharingFsmEffect.ReadyToSendPayloads }) streamFile(channel)
            return effects.any { it is SharingFsmEffect.Rejected }.also { if (it) log("receiver rejected") }
        }

        private suspend fun streamFile(channel: SecureChannel) {
            log("ACCEPT received; streaming ${fileBytes.size} bytes")
            val frames =
                PayloadTransferEncoder.encodeFilePayload(
                    payloadId = payloadId,
                    fileName = FILE_NAME,
                    totalSize = fileBytes.size.toLong(),
                    source = Channels.newChannel(ByteArrayInputStream(fileBytes)),
                )
            for (out in frames) channel.sendOfflineFrame(out)
            log("file streamed")
        }

        private suspend fun applyEffects(
            channel: SecureChannel,
            effects: List<SharingFsmEffect>,
        ) {
            for (effect in effects) {
                if (effect !is SharingFsmEffect.SendFrame) continue
                val id = secureRandom.nextLong()
                for (frame in PayloadTransferEncoder.encodeBytesPayload(id, effect.frame.toByteArray())) {
                    channel.sendOfflineFrame(frame)
                }
                log("sent sharing ${effect.frame.v1.type}")
            }
        }

        private fun connectionRequest(): OfflineFrame {
            val endpointInfo =
                EndpointInfo(
                    version = 1,
                    hidden = false,
                    deviceType = DeviceType.PHONE,
                    reserved = false,
                    metadata = ByteArray(EndpointInfo.METADATA_LEN) { (it + 1).toByte() },
                    deviceName = DEVICE_NAME,
                )
            val request =
                ConnectionRequestFrame
                    .newBuilder()
                    .setEndpointId(ENDPOINT_ID)
                    .setEndpointName(DEVICE_NAME)
                    .setEndpointInfo(ByteString.copyFrom(endpointInfo.serialize()))
                    .addMediums(ConnectionRequestFrame.Medium.WIFI_LAN)
                    .addMediums(ConnectionRequestFrame.Medium.WIFI_DIRECT)
                    .setKeepAliveIntervalMillis(KEEP_ALIVE_INTERVAL_MS)
                    .setKeepAliveTimeoutMillis(KEEP_ALIVE_TIMEOUT_MS)
            return OfflineFrame
                .newBuilder()
                .setVersion(OfflineFrame.Version.V1)
                .setV1(V1Frame.newBuilder().setType(V1Frame.FrameType.CONNECTION_REQUEST).setConnectionRequest(request))
                .build()
        }

        private fun connectionResponse(): OfflineFrame =
            OfflineFrame
                .newBuilder()
                .setVersion(OfflineFrame.Version.V1)
                .setV1(
                    V1Frame
                        .newBuilder()
                        .setType(V1Frame.FrameType.CONNECTION_RESPONSE)
                        .setConnectionResponse(
                            ConnectionResponseFrame
                                .newBuilder()
                                .setResponse(ConnectionResponseFrame.ResponseStatus.ACCEPT),
                        ),
                ).build()
    }

    private companion object {
        const val ENDPOINT_ID = "MACH"
        const val DEVICE_NAME = "Mac harness 288"
        const val FILE_NAME = "harness-288.bin"
        const val CONNECT_TIMEOUT_MS = 5_000
        const val TOTAL_TIMEOUT_MS = 180_000L
        const val KEEP_ALIVE_INTERVAL_MS = 5_000
        const val KEEP_ALIVE_TIMEOUT_MS = 15_000
        const val FILE_SIZE_BYTES = 300_000
    }
}
