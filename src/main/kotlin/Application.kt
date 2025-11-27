package net.nuclearprometheus

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.io.BufferedWriter
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

private val logger = LoggerFactory.getLogger("LocalWave")
private val listeners = CopyOnWriteArrayList<ByteWriteChannel>()
private val audioBuffer = ConcurrentLinkedQueue<ByteArray>()
private const val BUFFER_SIZE_CHUNKS = 150 // ~5 seconds at 8KB chunks

fun main() {
    logger.info("=== LocalWave Radio Server ===")

    validateEnvironment()
    createPlaylist()
    startBroadcastThread()
    startWebServer()
}

private fun validateEnvironment() {
    val ffmpeg = File("ffmpeg/ffmpeg.exe")
    val musicDir = File("music")

    require(ffmpeg.exists()) {
        "FFmpeg not found at: ${ffmpeg.absolutePath}"
    }
    require(musicDir.exists() && musicDir.isDirectory) {
        "Music directory not found at: ${musicDir.absolutePath}"
    }

    val mp3Count = musicDir.listFiles { file ->
        file.extension.equals("mp3", ignoreCase = true)
    }?.size ?: 0

    require(mp3Count > 0) {
        "No MP3 files found in music directory"
    }

    logger.info("Found $mp3Count MP3 files")
    logger.info("FFmpeg ready")
}

private fun createPlaylist() {
    val playlist = Paths.get("playlist.m3u")
    val musicDir = Paths.get("music")

    val mp3Files = Files.list(musicDir)
        .filter { it.toString().endsWith(".mp3", ignoreCase = true) }
        .toList()
        .shuffled()

    BufferedWriter(Files.newBufferedWriter(playlist)).use { writer ->
        mp3Files.forEach { file ->
            // Escape special characters for FFmpeg concat demuxer
            val escapedPath = file.toAbsolutePath().toString()
                .replace("\\", "/")  // Use forward slashes
                .replace("'", "'\\''")  // Escape single quotes

            writer.write("file '$escapedPath'")
            writer.newLine()
        }
    }

    logger.info("Playlist created with ${mp3Files.size} tracks")
}

private fun startBroadcastThread() {
    Thread {
        try {
            logger.info("Starting broadcast...")
            runBlocking {
                broadcastLoop()
            }
        } catch (e: Exception) {
            System.err.println("Broadcast error: ${e.message}")
            e.printStackTrace()
        }
    }.apply {
        isDaemon = false
        name = "FFmpeg-Broadcast"
        start()
    }

    // Give FFmpeg time to initialize and fill buffer
    Thread.sleep(3000)
    logger.info("Broadcast started (buffered)")
}

private suspend fun broadcastLoop() {
    withContext(Dispatchers.IO) {
        val ffmpeg = File("ffmpeg/ffmpeg.exe")
        val playlist = Paths.get("playlist.m3u")

        val process = ProcessBuilder(
            ffmpeg.absolutePath,
            "-re",
            "-stream_loop", "-1",
            "-f", "concat",
            "-safe", "0",
            "-i", playlist.toAbsolutePath().toString(),
            "-f", "mp3",
            "-loglevel", "warning",
            "-"
        ).start()

        // Monitor FFmpeg errors
        Thread {
            process.errorStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.isNotBlank()) {
                        logger.error("[FFmpeg] $line")
                    }
                }
            }
        }.start()

        val input = process.inputStream
        val buffer = ByteArray(8192)

        while (true) {
            val len = input.read(buffer)
            if (len == -1) {
                logger.error("FFmpeg stream ended unexpectedly")
                break
            }

            // Copy data for buffer
            val chunk = buffer.copyOf(len)

            // Add to circular buffer
            audioBuffer.offer(chunk)
            if (audioBuffer.size > BUFFER_SIZE_CHUNKS) {
                audioBuffer.poll() // Remove oldest
            }

            // Broadcast to all listeners
            broadcast(buffer, len)
        }
    }
}

private suspend fun broadcast(data: ByteArray, length: Int) {
    val deadChannels = mutableListOf<ByteWriteChannel>()

    for (channel in listeners) {
        try {
            channel.writeFully(data, 0, length)
            channel.flush()
        } catch (e: Exception) {
            deadChannels.add(channel)
        }
    }

    // Remove dead channels after iteration
    if (deadChannels.isNotEmpty()) {
        listeners.removeAll(deadChannels)
    }
}

private fun startWebServer() {
    val port = 8000
    logger.info("\n=== Starting web server on http://localhost:8000 ===\n")

    // Get LAN IP
    try {
        val lanIp = java.net.NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull {
                !it.isLoopbackAddress &&
                        it is java.net.Inet4Address
            }?.hostAddress

        logger.info("Server starting on:")
        logger.info("  Local:    http://127.0.0.1:$port")
        if (lanIp != null) {
            logger.info("  Network:  http://$lanIp:$port")
        }
    } catch (e: Exception) {
        logger.warn("Could not detect network addresses: ${e.message}")
    }

    embeddedServer(Netty, port = 8000, host = "0.0.0.0") {
        configureRouting()
    }.start(wait = true)
}

private fun Application.configureRouting() {
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
    }

    routing {
        get("/") {
            call.respondText(
                """
                <!DOCTYPE html>
                <html>
                <head>
                    <title>LocalWave Radio</title>
                    <style>
                        body { 
                            font-family: system-ui; 
                            max-width: 600px; 
                            margin: 100px auto; 
                            text-align: center;
                            background: #1a1a1a;
                            color: #fff;
                        }
                        audio { width: 100%; margin: 20px 0; }
                        h1 { font-size: 3em; margin-bottom: 10px; }
                        p { color: #888; }
                    </style>
                </head>
                <body>
                    <h1>📻 LocalWave</h1>
                    <p>Your personal radio station</p>
                    <audio controls autoplay>
                        <source src="/stream" type="audio/mpeg">
                    </audio>
                    <p style="font-size: 0.9em;">Currently streaming: ${listeners.size} listener(s)</p>
                </body>
                </html>
                """.trimIndent(),
                io.ktor.http.ContentType.Text.Html
            )
        }

        get("/stream") {
            call.response.header("Cache-Control", "no-cache")
            call.response.header("Connection", "keep-alive")
            call.response.header("Content-Type", "audio/mpeg")
            call.response.header("Accept-Ranges", "none")

            call.respondBytesWriter(contentType = ContentType.Audio.MPEG) {
                // Send buffered audio first (so new clients catch up)
                audioBuffer.forEach { chunk ->
                    try {
                        writeFully(chunk)
                        flush()
                    } catch (e: Exception) {
                        // Client disconnected during buffer send
                        return@respondBytesWriter
                    }
                }

                // Now add to live listeners
                listeners += this
                logger.info("Client connected (${listeners.size} total, sent ${audioBuffer.size} buffered chunks)")

                try {
                    awaitCancellation()
                } finally {
                    listeners -= this
                    logger.info("Client disconnected (${listeners.size} remaining)")
                }
            }
        }
    }
}
