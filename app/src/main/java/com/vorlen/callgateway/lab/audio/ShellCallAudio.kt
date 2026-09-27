package com.vorlen.callgateway.lab.audio

import android.content.Context
import com.vorlen.callgateway.lab.adb.AdbTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.math.sqrt
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
    private const val UPLINK_TEST = 85
    private const val UPLINK_PCM = 84
    private const val DUPLEX = 68
    private const val NONCE_BYTES = 16
    private const val MAC_BYTES = 32
    private val random = SecureRandom()
    private val daemonLabel = "jemrec-daemon".toByteArray()
    private val clientLabel = "jemrec-client".toByteArray()

    data class TestResult(val passed: Boolean, val report: String)
    data class ProofResult(val passed: Boolean, val report: String, val wav: File?)

    private const val RAW_SAMPLE_RATE = 48_000
    private const val RAW_CHANNELS = 2
    private const val RAW_SAMPLE_WIDTH = 2

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

    suspend fun bootstrapRaw(context: Context): Result<Unit> = bootstrapWithMode(context, raw = true)

    private suspend fun bootstrapWithMode(context: Context, raw: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            check(AdbTransport.isConnected) { "Wireless Debugging ADB is not connected" }
            val external = context.getExternalFilesDir(null) ?: error("External app directory unavailable")
            val staged = File(external, ASSET)
            context.assets.open(ASSET).use { input -> staged.outputStream().use { input.copyTo(it) } }
            val copy = AdbTransport.exec("cp '${staged.absolutePath}' $DEVICE_PATH && chmod 600 $DEVICE_PATH && echo staged").getOrThrow()
            check(copy.contains("staged")) { "Could not stage shell daemon: $copy" }
            val t = token(context)
            val rawArg = if (raw) " raw" else ""
            AdbTransport.exec(
                "pkill -f '[c]om.jemcik.jemrec.shell.Main' 2>/dev/null; " +
                    "JEMREC_TOKEN=$t JEMREC_MODE=0 CLASSPATH=$DEVICE_PATH setsid nohup " +
                    "app_process / $MAIN_CLASS $PORT$rawArg < /dev/null > $LOG_PATH 2>&1 &"
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


    class UplinkStream internal constructor(private val socket: Socket) : java.io.Closeable {
        private val out = java.io.DataOutputStream(socket.getOutputStream())
        @Synchronized fun write(pcm: ByteArray) {
            require(pcm.isNotEmpty() && pcm.size <= 192_000) { "PCM chunk must be 1..192000 bytes" }
            out.writeInt(pcm.size); out.write(pcm); out.flush()
        }
        override fun close() {
            runCatching { out.writeInt(0); out.flush() }
            runCatching { socket.close() }
        }
    }

    suspend fun openUplinkStream(context: Context): Result<UplinkStream> = withContext(Dispatchers.IO) {
        runCatching {
            if (!AdbTransport.isConnected) AdbTransport.autoConnect(context, 6000).getOrThrow()
            if (!ping()) bootstrapRaw(context).getOrThrow()
            val socket = open(context, DUPLEX.toByte())
            socket.soTimeout = 5000
            val ready = socket.getInputStream().bufferedReader().readLine() ?: error("No streaming uplink response")
            check(ready.startsWith("READY")) { "Streaming uplink rejected: $ready" }
            socket.soTimeout = 0
            UplinkStream(socket)
        }
    }

    suspend fun uplinkSpeech(context: Context, pcm: ByteArray): TestResult = withContext(Dispatchers.IO) {
        runCatching {
            require(pcm.isNotEmpty() && pcm.size <= 48_000 * 2 * 15) { "Speech PCM must be 0–15 seconds, 48 kHz mono PCM16" }
            if (!AdbTransport.isConnected) AdbTransport.autoConnect(context, 6000).getOrThrow()
            bootstrap(context).getOrThrow()
            open(context, UPLINK_PCM.toByte()).use { socket ->
                socket.soTimeout = 20_000
                val out = java.io.DataOutputStream(socket.getOutputStream())
                out.writeInt(pcm.size); out.write(pcm); out.flush()
                val response = socket.getInputStream().bufferedReader().readLine() ?: "No response from shell daemon"
                TestResult(response.startsWith("COMPLETE"), "DIGITAL SPEECH UPLINK — " + response)
            }
        }.getOrElse { TestResult(false, "DIGITAL SPEECH UPLINK FAILED — " + (it.message ?: it.javaClass.simpleName)) }
    }

    suspend fun uplinkTest(context: Context): TestResult = withContext(Dispatchers.IO) {
        runCatching {
            // Restart to guarantee the bundled daemon is this build, not an older resident process.
            if (!AdbTransport.isConnected) AdbTransport.autoConnect(context, 6000).getOrThrow()
            bootstrap(context).getOrThrow()
            open(context, UPLINK_TEST.toByte()).use { socket ->
                socket.soTimeout = 5000
                val response = socket.getInputStream().bufferedReader().readLine() ?: "No response from shell daemon"
                TestResult(response.startsWith("COMPLETE"), "SHELL UPLINK TEST — $response")
            }
        }.getOrElse { TestResult(false, "SHELL UPLINK TEST FAILED — " + (it.message ?: it.javaClass.simpleName)) }
    }

    suspend fun chatGptVoiceProbe(context: Context): TestResult = withContext(Dispatchers.IO) {
        runCatching {
            if (!AdbTransport.isConnected) AdbTransport.autoConnect(context, 6000).getOrThrow()
            val commands = listOf(
                "AUDIO MODE + OWNERS" to "dumpsys audio | grep -iE 'Audio mode|mode owner|communication|voice|record|playback' | head -n 180",
                "ACTIVE RECORD CLIENTS" to "dumpsys media.audio_flinger | grep -iE -B6 -A14 'Record Thread|RecordTrack|Active Tracks|session|uid|source|input' | head -n 260",
                "ACTIVE PLAYBACK CLIENTS" to "dumpsys media.audio_flinger | grep -iE -B6 -A14 'Playback Thread|Track|Active Tracks|session|uid|usage|output' | head -n 320",
                "POLICY INPUTS OUTPUTS" to "dumpsys media.audio_policy | grep -iE -B5 -A12 'Input|Output|active|session|uid|source|usage|remote.submix|telephony|voice' | head -n 420",
                "REMOTE SUBMIX + PATCHES" to "dumpsys media.audio_policy | grep -iE -B8 -A18 'remote.submix|AUDIO_DEVICE_(IN|OUT)_REMOTE_SUBMIX|audio patch|patches|mix port|mixport' | head -n 300",
                "CHATGPT PROCESS" to "ps -A -o USER,UID,PID,NAME,ARGS 2>/dev/null | grep -iE 'openai|chatgpt' | head -n 40",
                "AUDIO PORTS" to "dumpsys media.audio_flinger | grep -iE 'AUDIO_DEVICE_(IN|OUT)_(REMOTE_SUBMIX|TELEPHONY|VOICE_CALL)|Telephony (Tx|Rx)|Remote Submix' | head -n 160"
            )
            val sections = mutableListOf<String>()
            for ((label, cmd) in commands) {
                val output = AdbTransport.exec(cmd).getOrElse { "Unavailable: " + it.message }.trim()
                sections += "=== $label ===\n" + if (output.isBlank()) "(no matches)" else output
            }
            val report = sections.joinToString("\n\n")
            File(context.cacheDir, "vorlen_chatgpt_voice_probe.txt").writeText(report)
            TestResult(true, "CHATGPT VOICE LIVE AUDIO PROBE\nRun while ChatGPT Voice is actively speaking/listening.\n\n$report")
        }.getOrElse { TestResult(false, "CHATGPT VOICE PROBE FAILED — " + (it.message ?: it.javaClass.simpleName)) }
    }

    suspend fun audioDeviceSummary(context: Context): TestResult = withContext(Dispatchers.IO) {
        runCatching {
            if (!AdbTransport.isConnected) AdbTransport.autoConnect(context, 6000).getOrThrow()
            val commands = listOf(
                "CALL STATE" to "dumpsys audio | sed -n '/Audio mode:/,/Audio routes:/p' | head -n 28",
                "LIVE CALL ROUTE" to "dumpsys media.audio_flinger | grep -iE 'Call :|Output devices:|Input device:|AUDIO_DEVICE_(OUT|IN)_(EARPIECE|SPEAKER|TELEPHONY|VOICE_CALL)' | tail -n 40",
                "INCALL POLICY" to "dumpsys media.audio_policy | grep -iE 'incall|in.call|voice_tx|voice tx|voice_rx|voice rx|telephony|INCALL_MUSIC|mixport|mix port' | head -n 120",
                "VENDOR ROUTES" to "grep -RinE 'incall_music|INCALL_MUSIC|voice_tx|voice_rx|telephony_tx|telephony_rx' /vendor/etc/audio* /vendor/etc/*audio* /odm/etc/audio* /odm/etc/*audio* 2>/dev/null | head -n 120"
            )
            val sections = mutableListOf<String>()
            for ((label, cmd) in commands) {
                val output = AdbTransport.exec(cmd).getOrElse { "Unavailable: " + it.message }.trim()
                sections += "=== $label ===\n" + if (output.isBlank()) "(no matches)" else output
            }
            val report = sections.joinToString("\n\n")
            File(context.cacheDir, "vorlen_audio_summary.txt").writeText(report)
            TestResult(true, "UPLINK ROUTE PROBE\n\n$report")
        }.getOrElse { TestResult(false, "AUDIO ROUTE PROBE FAILED — " + it.message) }
    }

    data class SpeechTurn(val pcmMono48k: ByteArray, val durationMs: Long, val rms: Double, val peak: Int)

    suspend fun captureRemoteTurn(context: Context, maxSeconds: Int = 12): Result<SpeechTurn> = withContext(Dispatchers.IO) {
        runCatching {
            if (!AdbTransport.isConnected) AdbTransport.autoConnect(context, 6000).getOrThrow()
            // Always restart in RAW mode here. A resident daemon can be left behind by an older
            // APK/process with a different in-memory auth token even though its unauthenticated
            // PING still succeeds. Bootstrap uses this app's current token and removes that race.
            bootstrapRaw(context).getOrThrow()
            open(context, RECORD.toByte()).use { socket ->
                socket.soTimeout = 1500
                val input = DataInputStream(socket.getInputStream().buffered())
                val codecBytes = ByteArray(4).also(input::readFully)
                val codec = codecBytes.toString(Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
                check(codec.equals("raw", true)) { "Live turn capture requires raw daemon, got $codec" }
                val speech = java.io.ByteArrayOutputStream()
                var started = false
                var quietMs = 0L
                var speechMs = 0L
                var sq = 0.0
                var samples = 0L
                var peak = 0
                val deadline = System.currentTimeMillis() + maxSeconds * 1000L
                while (System.currentTimeMillis() < deadline && (!started || quietMs < 750L)) {
                    val pts = try { input.readLong() } catch (_: java.net.SocketTimeoutException) { continue }
                    val len = input.readInt()
                    require(len in 0..1_048_576)
                    val data = ByteArray(len).also(input::readFully)
                    if (pts and (1L shl 62) != 0L || len < 4) continue
                    val frames = len / 4
                    val mono = ByteArray(frames * 2)
                    var energy = 0.0
                    var chunkPeak = 0
                    var p = 0
                    for (i in 0 until frames) {
                        val l = ((data[p].toInt() and 255) or (data[p+1].toInt() shl 8)).toShort().toInt()
                        val r = ((data[p+2].toInt() and 255) or (data[p+3].toInt() shl 8)).toShort().toInt()
                        // Remote side was proven present in the stereo VOICE_CALL stream. Use the louder
                        // channel per frame so VAD remains robust across Samsung channel ordering.
                        val v = if (kotlin.math.abs(l) >= kotlin.math.abs(r)) l else r
                        mono[i*2] = (v and 255).toByte(); mono[i*2+1] = ((v shr 8) and 255).toByte()
                        energy += v.toDouble() * v
                        chunkPeak = maxOf(chunkPeak, kotlin.math.abs(v)); p += 4
                    }
                    val chunkRms = kotlin.math.sqrt(energy / frames.coerceAtLeast(1))
                    val chunkMs = frames * 1000L / RAW_SAMPLE_RATE
                    val active = chunkPeak > 500 && chunkRms > 30.0
                    if (active) {
                        started = true; quietMs = 0; speech.write(mono); speechMs += chunkMs
                        sq += energy; samples += frames; peak = maxOf(peak, chunkPeak)
                    } else if (started) {
                        speech.write(mono); speechMs += chunkMs; quietMs += chunkMs
                        sq += energy; samples += frames
                    }
                }
                check(started && speech.size() > 0) { "No speech turn detected" }
                SpeechTurn(speech.toByteArray(), speechMs, kotlin.math.sqrt(sq / samples.coerceAtLeast(1)), peak)
            }
        }
    }

    suspend fun proofCapture(context: Context, seconds: Int = 10): ProofResult = withContext(Dispatchers.IO) {
        runCatching {
            bootstrapRaw(context).getOrThrow()
            open(context, RECORD.toByte()).use { socket ->
                socket.soTimeout = 3000
                val input = DataInputStream(socket.getInputStream().buffered())
                val codecBytes = ByteArray(4).also(input::readFully)
                val codec = codecBytes.toString(Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
                check(codec.equals("raw", ignoreCase = true)) { "Expected raw PCM diagnostic stream, got '$codec'" }
                val pcm = java.io.ByteArrayOutputStream()
                var packets = 0
                val deadline = System.currentTimeMillis() + seconds * 1000L
                while (System.currentTimeMillis() < deadline) {
                    val pts = try { input.readLong() } catch (_: java.net.SocketTimeoutException) { continue }
                    val length = input.readInt()
                    require(length in 0..1_048_576) { "Invalid packet length $length at $pts" }
                    val data = ByteArray(length).also(input::readFully)
                    if (pts and (1L shl 62) == 0L) {
                        pcm.write(data)
                        packets++
                    }
                }
                val bytes = pcm.toByteArray()
                check(bytes.size >= RAW_CHANNELS * RAW_SAMPLE_WIDTH) { "Raw capture produced no PCM audio" }
                val frames = bytes.size / (RAW_CHANNELS * RAW_SAMPLE_WIDTH)
                var leftSq = 0.0
                var rightSq = 0.0
                var leftPeak = 0
                var rightPeak = 0
                var i = 0
                repeat(frames) {
                    val l = ((bytes[i].toInt() and 0xff) or (bytes[i + 1].toInt() shl 8)).toShort().toInt()
                    val r = ((bytes[i + 2].toInt() and 0xff) or (bytes[i + 3].toInt() shl 8)).toShort().toInt()
                    leftSq += l.toDouble() * l
                    rightSq += r.toDouble() * r
                    leftPeak = maxOf(leftPeak, kotlin.math.abs(l))
                    rightPeak = maxOf(rightPeak, kotlin.math.abs(r))
                    i += 4
                }
                val leftRms = sqrt(leftSq / frames)
                val rightRms = sqrt(rightSq / frames)
                val leftActive = leftPeak > 500 && leftRms > 30.0
                val rightActive = rightPeak > 500 && rightRms > 30.0
                val out = File(context.cacheDir, "vorlen_voice_call_proof.wav")
                writeWav(out, bytes, frames)
                val duration = frames.toDouble() / RAW_SAMPLE_RATE
                val verdict = when {
                    leftActive && rightActive -> "BOTH CHANNELS ACTIVE — confirm playback contains both people"
                    leftActive || rightActive -> "ONLY ONE CHANNEL ACTIVE — remote side may be missing"
                    else -> "BOTH CHANNELS QUIET — no meaningful speech detected"
                }
                ProofResult(
                    leftActive && rightActive,
                    "PROOF — %.1fs, %d packets\nLeft RMS %.1f / peak %d: %s\nRight RMS %.1f / peak %d: %s\n%s".format(
                        duration, packets, leftRms, leftPeak, if (leftActive) "ACTIVE" else "quiet",
                        rightRms, rightPeak, if (rightActive) "ACTIVE" else "quiet", verdict
                    ),
                    out
                )
            }
        }.getOrElse { ProofResult(false, "PROOF FAILED — ${it.javaClass.simpleName}: ${it.message}", null) }
    }

    private fun writeWav(file: File, pcm: ByteArray, frames: Int) {
        FileOutputStream(file).use { out ->
            val dataSize = frames * RAW_CHANNELS * RAW_SAMPLE_WIDTH
            fun le16(v: Int) { out.write(v and 0xff); out.write((v ushr 8) and 0xff) }
            fun le32(v: Int) { out.write(v and 0xff); out.write((v ushr 8) and 0xff); out.write((v ushr 16) and 0xff); out.write((v ushr 24) and 0xff) }
            out.write("RIFF".toByteArray()); le32(36 + dataSize); out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray()); le32(16); le16(1); le16(RAW_CHANNELS); le32(RAW_SAMPLE_RATE)
            le32(RAW_SAMPLE_RATE * RAW_CHANNELS * RAW_SAMPLE_WIDTH); le16(RAW_CHANNELS * RAW_SAMPLE_WIDTH); le16(RAW_SAMPLE_WIDTH * 8)
            out.write("data".toByteArray()); le32(dataSize); out.write(pcm, 0, dataSize)
        }
    }

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
