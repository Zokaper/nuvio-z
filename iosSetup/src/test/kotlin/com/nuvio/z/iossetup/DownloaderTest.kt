package com.nuvio.z.iossetup

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DownloaderTest {
    private val payload = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
    private val dir: Path = Files.createTempDirectory("downloader")
    private val servers = mutableListOf<HttpServer>()
    private val fast = { Downloader(attempts = 3, backoffMillis = 1) }

    @AfterTest fun stop() = servers.forEach { it.stop(0) }

    private fun serve(handler: (com.sun.net.httpserver.HttpExchange, AtomicInteger) -> Unit): Pair<String, AtomicInteger> {
        val hits = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/file") { exchange -> hits.incrementAndGet(); handler(exchange, hits); exchange.close() }
        server.start(); servers += server
        return "http://127.0.0.1:${server.address.port}/file" to hits
    }

    private fun ok(exchange: com.sun.net.httpserver.HttpExchange, body: ByteArray = payload) {
        exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.write(body)
    }

    @Test fun verifiedDownloadLandsAtTheDestinationAndLeavesNoPartFile() {
        val (url, _) = serve { e, _ -> ok(e) }
        val target = dir.resolve("a.bin")
        val result = fast().download(url, target, sha, payload.size.toLong())
        assertIs<DownloadResult.Done>(result)
        assertEquals(sha, Downloader.sha256Of(target))
        assertFalse(Files.exists(dir.resolve("a.bin.part")))
    }

    @Test fun wrongChecksumIsRejectedAndTheBytesDeleted() {
        val (url, _) = serve { e, _ -> ok(e) }
        val target = dir.resolve("b.bin")
        val result = fast().download(url, target, "0".repeat(64), payload.size.toLong())
        assertEquals(DownloadResult.Reason.CHECKSUM, (result as DownloadResult.Failed).reason)
        assertFalse(Files.exists(target)); assertFalse(Files.exists(dir.resolve("b.bin.part")))
    }

    @Test fun wrongSizeIsRejected() {
        val (url, _) = serve { e, _ -> ok(e) }
        val result = fast().download(url, dir.resolve("c.bin"), null, expectedBytes = 5)
        assertEquals(DownloadResult.Reason.SIZE, (result as DownloadResult.Failed).reason)
    }

    @Test fun minimumSizeCatchesATruncatedInstallerWithoutAHashPin() {
        val (url, _) = serve { e, _ -> ok(e) }
        val result = fast().download(url, dir.resolve("d.bin"), null, minBytes = 10_000_000)
        assertEquals(DownloadResult.Reason.SIZE, (result as DownloadResult.Failed).reason)
    }

    @Test fun serverErrorsAreRetriedThenSucceed() {
        val (url, hits) = serve { e, n -> if (n.get() < 3) e.sendResponseHeaders(503, -1) else ok(e) }
        assertIs<DownloadResult.Done>(fast().download(url, dir.resolve("e.bin"), sha, payload.size.toLong()))
        assertEquals(3, hits.get())
    }

    @Test fun aMissingFileFailsFastWithoutRetrying() {
        val (url, hits) = serve { e, _ -> e.sendResponseHeaders(404, -1) }
        val result = fast().download(url, dir.resolve("f.bin"), sha, payload.size.toLong())
        assertEquals(DownloadResult.Reason.HTTP, (result as DownloadResult.Failed).reason)
        assertEquals(1, hits.get())
    }

    @Test fun interruptedDownloadResumesFromThePartFile() {
        val (url, _) = serve { e, _ ->
            val range = e.requestHeaders.getFirst("Range")
            if (range == null) ok(e) else {
                val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
                e.responseHeaders.add("Content-Range", "bytes $from-${payload.size - 1}/${payload.size}")
                val rest = payload.copyOfRange(from, payload.size)
                e.sendResponseHeaders(206, rest.size.toLong()); e.responseBody.write(rest)
            }
        }
        val target = dir.resolve("g.bin")
        Files.write(dir.resolve("g.bin.part"), payload.copyOfRange(0, 120_000))
        assertIs<DownloadResult.Done>(fast().download(url, target, sha, payload.size.toLong()))
        assertEquals(sha, Downloader.sha256Of(target))
    }

    @Test fun progressIsReportedMonotonically() {
        val (url, _) = serve { e, _ -> ok(e) }
        val seen = mutableListOf<Long>()
        fast().download(url, dir.resolve("h.bin"), sha, payload.size.toLong(), onProgress = { done, _ -> seen += done })
        assertTrue(seen.isNotEmpty() && seen == seen.sorted() && seen.last() == payload.size.toLong())
    }

    @Test fun unreachableHostFailsAsNetworkAfterTheConfiguredAttempts() {
        val result = Downloader(attempts = 2, backoffMillis = 1).download("http://127.0.0.1:1/x", dir.resolve("i.bin"), sha)
        assertEquals(DownloadResult.Reason.NETWORK, (result as DownloadResult.Failed).reason)
    }
}
