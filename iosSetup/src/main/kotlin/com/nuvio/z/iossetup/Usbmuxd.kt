package com.nuvio.z.iossetup

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * macOS's own usbmuxd, queried the same way iloader reaches the phone. A USB entry in its
 * device list is the authoritative "this Mac can talk to the iPhone" signal; system_profiler's
 * USB report is empty on Apple silicon under recent macOS, so it cannot be the gate.
 */
object Usbmuxd {
    val macSocket: Path = Path.of("/var/run/usbmuxd")

    private const val HEADER_BYTES = 16
    private const val PLIST_VERSION = 1
    private const val PLIST_MESSAGE = 8

    fun listDevicesRequest(tag: Int = 1): ByteArray {
        val plist = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict><key>MessageType</key><string>ListDevices</string><key>ClientVersionString</key><string>nuvio-z-ios-setup</string><key>ProgName</key><string>Nuvio Z iOS Setup</string><key>kLibUSBMuxVersion</key><integer>3</integer></dict></plist>
""".toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(HEADER_BYTES + plist.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(HEADER_BYTES + plist.size).putInt(PLIST_VERSION).putInt(PLIST_MESSAGE).putInt(tag)
            .put(plist).array()
    }

    /** Wi-Fi-synced phones appear as "Network"; only a USB attachment counts for setup. */
    fun usbDeviceCount(responsePlist: String): Int =
        Regex("<key>ConnectionType</key>\\s*<string>USB</string>").findAll(responsePlist).count()

    fun isReachable(socket: Path = macSocket): Boolean = runCatching {
        SocketChannel.open(StandardProtocolFamily.UNIX).use { it.connect(UnixDomainSocketAddress.of(socket)) }
        true
    }.getOrDefault(false)

    /** Number of USB-attached devices, or null when usbmuxd could not be queried. */
    fun usbDeviceCount(socket: Path = macSocket, timeoutMillis: Long = 3_000): Int? {
        val channel = runCatching { SocketChannel.open(StandardProtocolFamily.UNIX) }.getOrNull() ?: return null
        val query = CompletableFuture.supplyAsync {
            channel.connect(UnixDomainSocketAddress.of(socket))
            val request = ByteBuffer.wrap(listDevicesRequest())
            while (request.hasRemaining()) channel.write(request)
            val header = readFully(channel, HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            val length = header.getInt(0)
            require(length in HEADER_BYTES..(4 * 1024 * 1024)) { "Unexpected usbmuxd reply length $length" }
            val body = readFully(channel, length - HEADER_BYTES)
            usbDeviceCount(String(body.array(), Charsets.UTF_8))
        }
        return try {
            query.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { channel.close() }
        }
    }

    private fun readFully(channel: SocketChannel, bytes: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(bytes)
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) error("usbmuxd closed the connection")
        }
        return buffer
    }
}
