package com.vorlen.callgateway.lab.audio

import android.content.Context
import com.vorlen.callgateway.lab.adb.AdbTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object ShellCallAudio {
    private const val ASSET = "vorlen-call-capture.jar"
    private const val DEVICE_PATH = "/data/local/tmp/vorlen-call-capture.jar"
    private const val LOG_PATH = "/data/local/tmp/vorlen-call-capture.out"
    private const val MAIN_CLASS = "com.jemcik.jemrec.shell.Main"
    private const val PORT = 28472
    private const val HELLO = 65
    private const val RECORD = 82
    private const val NONCE_BYTES = 16
    private const val MAC_BYTES = 32
    private val random = SecureRandom()
    private val daemonLabel = "jemrec-daemon".toByteArray()
    private val clientLabel = "jemrec-client".toByteArray()

    data class TestResult(val passed: Boolean, val report: String)

    private fun token(context: Context): String {
        val prefs = context.getSharedPreferences("audio_lab", Context.MODE_PRIVATE)
        prefs.getString("daemon_token", null)?.let { return it }
        val fresh = ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        prefs.edit().putString("daemon_token", fresh).apply()
        return fresh
    }

    suspend fun bootstrap(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            check(AdbTransport.isConnected) { "Wireless Debugging ADB is not connected" }
            val external = context.getExternalFilesDir(null) ?: error("External app directory unavailable")
            val staged = File(external, ASSET)
            context.assets.open(ASSET).use { input -> staged.outputStream().use { input.copyTo(it) } }
            val copy = AdbTransport.exec("cp '${staged.absolutePath}' $DEVICE_PATH && chmod 600 $DEVICE_PATH && echo staged").getOrThrow()
            check(copy.contains("staged")) { "Could not stage shell daemon: $copy" }
            val t = token(context)
            AdbTransport.exec(
                "pkill -f '[c]om.jemcik.jemrec.shell.Main' 2>/dev/null; " +
                    "JEMREC_TOKEN=$t JEMREC_MODE=0 CLASSPATH=$DEVICE_PATH setsid nohup " +
                    "app_process / $MAIN_CLASS $PORT < /dev/null > $LOG_PATH 2>&1 &"
            ).getOrThrow()
            var ready = false
            repeat(20) {
                if (!ready) {
                    Thread.sleep(250)
                    ready = ping()
                }
            }
            if (!ready) {
                val log = AdbTransport.exec("cat $LOG_PATH").getOrElse { it.message ?: "unavailable" }
                error("Shell daemon did not start. $log")
            }
        }
    }

    fun ping(): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", PORT), 1000)
            s.soTimeout = 1500
            s.getOutputStream().apply { write(byteArrayOf('P'.code.toByte())); flush() }
            s.getInputStream().bufferedReader().readLine()?.startsWith("PONG") == true
        }
    } catch (_: Throwable) { false }

    suspend fun selfTest(context: Context): TestResult = withContext(Dispatchers.IO) {
        runCatching {
            open(context, RECORD.toByte()).use { socket ->
                val input = DataInputStream(socket.getInputStream().buffered())
                val codec = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII).trim()
                var packets = 0
                var bytes = 0
                val deadline = System.currentTimeMillis() + 6000
                while (packets < 5 && System.currentTimeMillis() < deadline) {
                    val pts = try { input.readLong() } catch (_: EOFException) { break }
                    val length = input.readInt()
                    require(length in 0..1_048_576) { "Invalid packet length $length at $pts" }
                    val data = ByteArray(length).also(input::readFully)
                    val isConfig = pts and (1L shl 62) != 0L
                    if (!isConfig) { packets++; bytes += data.size }
                }
                check(packets > 0 && bytes > 0) { "Capture opened but produced no call-audio packets" }
                TestResult(true, "PASS — shell VOICE_CALL stream: $codec, $packets packets, $bytes bytes")
            }
        }.getOrElse { TestResult(false, "FAIL — ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun open(context: Context, command: Byte): Socket {
        val socket = Socket()
        socket.connect(InetSocketAddress("127.0.0.1", PORT), 1500)
        socket.soTimeout = 10_000
        try {
            val key = token(context).toByteArray()
            val ours = ByteArray(NONCE_BYTES).also(random::nextBytes)
            val out = socket.getOutputStream()
            out.write(byteArrayOf(HELLO.toByte())); out.write(ours); out.flush()
            val input = DataInputStream(socket.getInputStream())
            val theirs = ByteArray(NONCE_BYTES); val proof = ByteArray(MAC_BYTES)
            input.readFully(theirs); input.readFully(proof)
            check(MessageDigest.isEqual(proof, mac(key, daemonLabel, ours, theirs))) { "Daemon authentication failed" }
            out.write(mac(key, clientLabel, ours, theirs)); out.write(byteArrayOf(command)); out.flush()
            return socket
        } catch (t: Throwable) {
            runCatching { socket.close() }
            throw t
        }
    }

    private fun mac(key: ByteArray, label: ByteArray, a: ByteArray, b: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256")); update(label); update(a); doFinal(b)
        }
}
