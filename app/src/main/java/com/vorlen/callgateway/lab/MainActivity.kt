package com.vorlen.callgateway.lab

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.media.MediaPlayer
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.media.MediaExtractor
import android.media.MediaCodec
import java.nio.ByteBuffer
import java.util.Locale
import java.io.File
import android.os.Bundle
import android.content.Intent
import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import java.util.concurrent.Executors
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.vorlen.callgateway.lab.adb.AdbTransport
import com.vorlen.callgateway.lab.audio.ShellCallAudio
import kotlinx.coroutines.runBlocking

class MainActivity : AppCompatActivity() {
    private val requestCode = 100
    private lateinit var stateView: TextView
    private val pollingIo = Executors.newSingleThreadExecutor()
    private val outboundIo = Executors.newSingleThreadExecutor()
    @Volatile private var polling = false
    @Volatile private var activeRequestId: String? = null
    @Volatile private var sawOffHook = false
    @Volatile private var lastCallState = TelephonyManager.CALL_STATE_IDLE
    @Volatile private var sessionApproved = false
    private var proofPlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val status = findViewById<TextView>(R.id.status)
        stateView = findViewById(R.id.callState)
        val number = findViewById<EditText>(R.id.number)
        val remoteState = findViewById<TextView>(R.id.remoteState)
        val pairingToken = findViewById<EditText>(R.id.pairingToken)
        val sessionState = findViewById<TextView>(R.id.sessionState)
        val prefs = getSharedPreferences("gateway", MODE_PRIVATE)
        pairingToken.setText(prefs.getString("device_token", ""))
        val laptopIpView = findViewById<EditText>(R.id.laptopIp)
        val laptopPortView = findViewById<EditText>(R.id.laptopPort)
        laptopIpView.setText(prefs.getString("laptop_host", "192.168.1.4"))
        laptopPortView.setText(prefs.getInt("laptop_port", 28761).toString())
        handleApprovedIntent(intent, number, status)
        createApprovalChannel()

        val permissions = buildList {
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.ANSWER_PHONE_CALLS)
            add(Manifest.permission.RECORD_AUDIO)
            if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
        if (permissions.any { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            ActivityCompat.requestPermissions(this, permissions, requestCode)
        }

        @Suppress("DEPRECATION")
        (getSystemService(TELEPHONY_SERVICE) as TelephonyManager).listen(object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                lastCallState = state
                val stateName = when (state) {
                    TelephonyManager.CALL_STATE_RINGING -> "ringing"
                    TelephonyManager.CALL_STATE_OFFHOOK -> "active"
                    else -> "idle"
                }
                if (state == TelephonyManager.CALL_STATE_OFFHOOK) sawOffHook = true
                val requestId = activeRequestId
                if (requestId != null) {
                    sendEvent("call_state", stateName, requestId)
                    if (state == TelephonyManager.CALL_STATE_IDLE && sawOffHook) {
                        completeActiveRequest(requestId)
                        activeRequestId = null
                        sawOffHook = false
                    }
                }
                stateView.text = "Call state: " + when (state) {
                    TelephonyManager.CALL_STATE_RINGING -> "ringing"
                    TelephonyManager.CALL_STATE_OFFHOOK -> "active"
                    else -> "idle / ended"
                }
            }
        }, PhoneStateListener.LISTEN_CALL_STATE)

        val adbPairPort = findViewById<EditText>(R.id.adbPairPort)
        val adbPairCode = findViewById<EditText>(R.id.adbPairCode)
        val audioState = findViewById<TextView>(R.id.audioState)
        findViewById<Button>(R.id.pairAdb).setOnClickListener {
            val port = adbPairPort.text.toString().toIntOrNull()
            val code = adbPairCode.text.toString().trim()
            if (port == null || code.length != 6) {
                audioState.text = "Audio engine: enter the pairing port and 6-digit code shown by Android"
            } else {
                audioState.text = "Audio engine: pairing with this phone…"
                outboundIo.execute {
                    val result = runBlocking {
                        AdbTransport.pair(this@MainActivity, AdbTransport.LOOPBACK, port, code)
                            .fold(
                                onSuccess = { AdbTransport.autoConnect(this@MainActivity, 6000) },
                                onFailure = { Result.failure(it) },
                            )
                    }
                    runOnUiThread {
                        audioState.text = result.fold(
                            onSuccess = { "Audio engine: Wireless Debugging paired and ADB connected" },
                            onFailure = { "Audio engine: pairing/connect failed — ${it.message}" },
                        )
                    }
                }
            }
        }

        findViewById<Button>(R.id.startAudioDaemon).setOnClickListener {
            audioState.text = "Audio engine: starting shell daemon…"
            outboundIo.execute {
                val result = runBlocking {
                    if (!AdbTransport.isConnected) {
                        AdbTransport.autoConnect(this@MainActivity, 6000).getOrElse {
                            return@runBlocking Result.failure<Unit>(it)
                        }
                    }
                    ShellCallAudio.bootstrap(this@MainActivity)
                }
                runOnUiThread {
                    audioState.text = result.fold(
                        onSuccess = { "Audio engine: shell daemon running and authenticated" },
                        onFailure = { "Audio engine: daemon start failed — ${it.message}" },
                    )
                }
            }
        }

        findViewById<Button>(R.id.shellAudioSelfTest).setOnClickListener {
            audioState.text = "Audio engine: reading shell VOICE_CALL stream…"
            outboundIo.execute {
                val result = runBlocking { ShellCallAudio.selfTest(this@MainActivity) }
                runOnUiThread { audioState.text = result.report }
            }
        }

        findViewById<Button>(R.id.probeAudioRoutes).setOnClickListener {
            audioState.text = "Audio engine: reading cellular audio device routes…"
            outboundIo.execute {
                val result = runBlocking { ShellCallAudio.audioDeviceSummary(this@MainActivity) }
                runOnUiThread { audioState.text = result.report }
            }
        }

        findViewById<Button>(R.id.probeChatGptVoice).setOnClickListener {
            audioState.text = "Probing live ChatGPT Voice input/output routes… keep ChatGPT Voice actively speaking/listening"
            outboundIo.execute {
                val result = runBlocking { ShellCallAudio.chatGptVoiceProbe(this@MainActivity) }
                runOnUiThread { audioState.text = result.report }
            }
        }

        findViewById<Button>(R.id.startLaptopBridge).setOnClickListener {
            // PhoneStateListener can report IDLE on some Samsung/One UI builds even
            // while the cellular audio route is active. The shell daemon performs the
            // authoritative MODE_IN_CALL check and fails closed when there is no call.
            val host = findViewById<EditText>(R.id.laptopIp).text.toString().trim()
            val port = findViewById<EditText>(R.id.laptopPort).text.toString().toIntOrNull() ?: 28761
            if (host.isBlank()) {
                audioState.text = "Enter the laptop LAN IP address"
            } else {
                audioState.text = "LAPTOP BRIDGE — checking cellular audio route and connecting to $host:$port…"
                outboundIo.execute {
                    val result = runBlocking {
                        ShellCallAudio.runLaptopNetworkBridge(this@MainActivity, host, port) { ready ->
                            runOnUiThread {
                                audioState.text = "LAPTOP BRIDGE ACTIVE\n\nTELEPHONY DIAGNOSTICS\n$ready\n\nKeep this screen open or take a screenshot. These are the actual shell Telephony TX parameters."
                            }
                        }
                    }
                    runOnUiThread { audioState.text = result.report }
                }
            }
        }

        findViewById<Button>(R.id.testChatGptCallBridge).setOnClickListener {
            if (lastCallState != TelephonyManager.CALL_STATE_OFFHOOK) {
                audioState.text = "CHATGPT → CALL BRIDGE BLOCKED — no active cellular call"
            } else {
                audioState.text = "Bridge armed for 60 seconds. Switch to ChatGPT Voice NOW and make ChatGPT speak."
                outboundIo.execute {
                    val result = runBlocking { ShellCallAudio.chatGptToCallBridge(this@MainActivity) }
                    runOnUiThread { audioState.text = result.report }                }
            }        }

        findViewById<Button>(R.id.testCallToChatGptBridge).setOnClickListener {
            if (lastCallState != TelephonyManager.CALL_STATE_OFFHOOK) {
                audioState.text = "CALL → CHATGPT BRIDGE BLOCKED — no active cellular call"
            } else {
                audioState.text = "Reverse bridge armed for 60 seconds. Switch to ChatGPT Voice; ask the remote caller to speak."
                outboundIo.execute {
                    val result = runBlocking { ShellCallAudio.callToChatGptBridge(this@MainActivity) }
                    runOnUiThread { audioState.text = result.report }
                }
            }
        }

        findViewById<Button>(R.id.testIncallUplink).setOnClickListener {
            if (lastCallState != TelephonyManager.CALL_STATE_OFFHOOK) {
                audioState.text = "UPLINK TEST BLOCKED — no active cellular call"
            } else {
                audioState.text = "Testing 1-second digital uplink tone from shell UID…"
                outboundIo.execute {
                    val result = runBlocking { ShellCallAudio.uplinkTest(this@MainActivity) }
                    runOnUiThread { audioState.text = result.report }
                }
            }
        }

        findViewById<Button>(R.id.testSpeechUplink).setOnClickListener {
            if (lastCallState != TelephonyManager.CALL_STATE_OFFHOOK) {
                audioState.text = "DIGITAL SPEECH UPLINK BLOCKED — no active cellular call"
            } else {
                val phrase = findViewById<EditText>(R.id.uplinkSpeechText).text.toString().trim()
                if (phrase.isBlank()) {
                    audioState.text = "Enter a speech test phrase"
                } else {
                    audioState.text = "Generating speech for digital Telephony Tx…"
                    lateinit var tts: TextToSpeech
                    tts = TextToSpeech(this) { statusCode ->
                        if (statusCode != TextToSpeech.SUCCESS) {
                            runOnUiThread { audioState.text = "TTS initialization failed" }
                        } else {
                            tts.language = Locale.UK
                            val outFile = File(cacheDir, "vorlen_uplink_speech.wav")
                            tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                                override fun onStart(id: String?) {}
                                override fun onError(id: String?) { runOnUiThread { audioState.text = "TTS synthesis failed" }; tts.shutdown() }
                                override fun onDone(id: String?) {
                                    outboundIo.execute {
                                        val result = runCatching {
                                            val pcm = wavTo48kMonoPcm(outFile)
                                            runBlocking { ShellCallAudio.uplinkSpeech(this@MainActivity, pcm) }
                                        }.getOrElse { ShellCallAudio.TestResult(false, "DIGITAL SPEECH UPLINK FAILED — " + (it.message ?: it.javaClass.simpleName)) }
                                        runOnUiThread { audioState.text = result.report }
                                        tts.shutdown()
                                    }
                                }
                            })
                            val r = tts.synthesizeToFile(phrase, Bundle(), outFile, "vorlen-uplink")
                            if (r != TextToSpeech.SUCCESS) {
                                audioState.text = "TTS synthesis request failed"
                                tts.shutdown()
                            }
                        }
                    }
                }
            }
        }

        findViewById<Button>(R.id.testStreamingSpeech).setOnClickListener {
            if (lastCallState != TelephonyManager.CALL_STATE_OFFHOOK) {
                audioState.text = "STREAMING UPLINK BLOCKED — no active cellular call"
            } else {
                val phrase = findViewById<EditText>(R.id.uplinkSpeechText).text.toString().trim()
                audioState.text = "Opening persistent digital Telephony Tx stream…"
                lateinit var tts: TextToSpeech
                tts = TextToSpeech(this) { statusCode ->
                    if (statusCode != TextToSpeech.SUCCESS) {
                        audioState.text = "Streaming TTS initialization failed"
                    } else {
                        tts.language = Locale.UK
                        val outFile = File(cacheDir, "vorlen_stream_speech.wav")
                        tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                            override fun onStart(id: String?) {}
                            override fun onError(id: String?) { runOnUiThread { audioState.text = "Streaming TTS synthesis failed" }; tts.shutdown() }
                            override fun onDone(id: String?) {
                                outboundIo.execute {
                                    val result = runCatching {
                                        val pcm = wavTo48kMonoPcm(outFile)
                                        val stream = runBlocking { ShellCallAudio.openUplinkStream(this@MainActivity).getOrThrow() }
                                        stream.use {
                                            var off = 0
                                            val chunk = 9600 // 100 ms @ 48k mono PCM16
                                            while (off < pcm.size) {
                                                val end = minOf(off + chunk, pcm.size)
                                                it.write(pcm.copyOfRange(off, end))
                                                off = end
                                            }
                                        }
                                        ShellCallAudio.TestResult(true, "STREAMING DIGITAL SPEECH — SENT " + pcm.size + " bytes in 100 ms PCM chunks to Telephony Tx")
                                    }.getOrElse { ShellCallAudio.TestResult(false, "STREAMING DIGITAL SPEECH FAILED — " + (it.message ?: it.javaClass.simpleName)) }
                                    runOnUiThread { audioState.text = result.report }
                                    tts.shutdown()
                                }
                            }
                        })
                        if (tts.synthesizeToFile(phrase, Bundle(), outFile, "vorlen-stream-uplink") != TextToSpeech.SUCCESS) {
                            audioState.text = "Streaming TTS request failed"; tts.shutdown()
                        }
                    }
                }
            }
        }

        findViewById<Button>(R.id.testAlexTurn).setOnClickListener {
            if (lastCallState != TelephonyManager.CALL_STATE_OFFHOOK) audioState.text = "ALEX TURN BLOCKED — no active cellular call"
            else {
                val token=prefs.getString("device_token",null)
                if(token.isNullOrBlank()) audioState.text="ALEX TURN BLOCKED — pair the Vorlen gateway first"
                else {
                    audioState.text="ALEX LIVE — listening to the remote person digitally…"
                    outboundIo.execute {
                        try {
                            val turn=runBlocking { ShellCallAudio.captureRemoteTurn(this@MainActivity,12).getOrThrow() }
                            runOnUiThread { audioState.text="ALEX LIVE — speech detected; transcribing and thinking…" }
                            val (heard,reply)=liveTurnRequest(token,turn.pcmMono48k)
                            if(reply.isBlank()) throw IllegalStateException("No Alex response generated")
                            runOnUiThread { audioState.text="HEARD: $heard\nALEX: $reply\nStreaming reply to Telephony Tx…" }
                            speakAlexStreaming(reply){ err -> runOnUiThread { audioState.text=if(err==null) "ONE-TURN AI PASS\nHEARD: $heard\nALEX: $reply\nReply streamed digitally to Telephony Tx." else "ALEX UPLINK FAILED — $err" } }
                        } catch(e:Throwable){ runOnUiThread { audioState.text="ALEX TURN FAILED — "+(e.message?:e.javaClass.simpleName) } }
                    }
                }
            }
        }

        findViewById<Button>(R.id.testLiveTurn).setOnClickListener {
            if (lastCallState != TelephonyManager.CALL_STATE_OFFHOOK) audioState.text = "LIVE TURN BLOCKED — no active cellular call"
            else {
                audioState.text = "LIVE TURN — listening digitally; remote person speak, then pause…"
                outboundIo.execute {
                    val result = runBlocking { ShellCallAudio.captureRemoteTurn(this@MainActivity, 12) }
                    runOnUiThread { audioState.text = result.fold(
                        onSuccess = { turn -> "LIVE TURN DETECTED — duration=" + turn.durationMs + "ms, bytes=" + turn.pcmMono48k.size + ", RMS=" + String.format("%.1f", turn.rms) + ", peak=" + turn.peak + "\nTurn ended after digital silence detection." },
                        onFailure = { err -> "LIVE TURN FAILED — " + err.javaClass.simpleName + ": " + err.message }
                    ) }
                }
            }
        }

        val playCallProof = findViewById<Button>(R.id.playCallProof)
        findViewById<Button>(R.id.proofCallAudio).setOnClickListener {
            audioState.text = "Audio engine: capturing 10 seconds of raw stereo VOICE_CALL audio… keep both people talking"
            playCallProof.isEnabled = false
            outboundIo.execute {
                val result = runBlocking { ShellCallAudio.proofCapture(this@MainActivity, 10) }
                runOnUiThread {
                    audioState.text = result.report
                    playCallProof.isEnabled = result.wav?.exists() == true
                }
            }
        }

        playCallProof.setOnClickListener {
            val wav = File(cacheDir, "vorlen_voice_call_proof.wav")
            if (!wav.exists()) {
                audioState.text = "Audio engine: no proof recording available yet"
            } else {
                runCatching {
                    proofPlayer?.release()
                    proofPlayer = MediaPlayer().apply {
                        setDataSource(wav.absolutePath)
                        prepare()
                        setOnCompletionListener { p -> p.release(); proofPlayer = null }
                        start()
                    }
                    audioState.text = "Audio engine: playing captured digital call proof"
                }.onFailure {
                    audioState.text = "Audio engine: playback failed — ${it.message}"
                }
            }
        }

        findViewById<Button>(R.id.audioSelfTest).setOnClickListener {
            audioState.text = "Audio engine: testing MIC and protected VOICE_CALL…"
            outboundIo.execute {
                val mic = CallAudioDiagnostics.microphone(this)
                val voice = CallAudioDiagnostics.protectedVoiceCall(this)
                runOnUiThread {
                    audioState.text = buildString {
                        append("MIC: ")
                        append(if (mic.hasSignal) "signal detected" else mic.error ?: "no signal")
                        append("\nVOICE_CALL (normal app UID): ")
                        append(if (voice.hasSignal) "signal detected" else voice.error ?: "blocked/no signal")
                        append("\n")
                        append(
                            if (voice.hasSignal) "Protected source is directly available on this device."
                            else "Expected on modern Android: normal app UID cannot prove digital call capture. Shell-daemon test is the next stage."
                        )
                    }
                }            }
        }
        findViewById<Button>(R.id.approveSession).setOnClickListener {
            sessionApproved = !sessionApproved
            if (sessionApproved) {
                val bridgeHost = findViewById<EditText>(R.id.laptopIp).text.toString().trim()
                val bridgePort = findViewById<EditText>(R.id.laptopPort).text.toString().toIntOrNull() ?: 28761
                prefs.edit().putString("laptop_host", bridgeHost).putInt("laptop_port", bridgePort).apply()
                androidx.core.content.ContextCompat.startForegroundService(this, Intent(this, GatewayService::class.java))
                polling = false
            } else {
                stopService(Intent(this, GatewayService::class.java))
            }
            sessionState.text = if (sessionApproved) "Calling session: APPROVED — background gateway active" else "Calling session: not approved"
            findViewById<Button>(R.id.approveSession).text = if (sessionApproved) "End Calling Session" else "Approve Calling Session"
        }

        findViewById<Button>(R.id.pairGateway).setOnClickListener {
            val token = pairingToken.text.toString().trim()
            if (token.length < 24) remoteState.text = "Remote gateway: enter pairing credential"
            else { prefs.edit().putString("device_token", token).apply(); testGateway(token, remoteState) }
        }
        prefs.getString("device_token", null)?.takeIf { it.length >= 24 }?.let { testGateway(it, remoteState) }

        findViewById<Button>(R.id.call).setOnClickListener {
            val result = placeSimCall(number.text.toString().trim())
            status.text = result.message
        }

        findViewById<Button>(R.id.hangup).setOnClickListener {
            status.text = endSimCall().message
        }
    }

    private fun pcm48MonoToWav(pcm: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(44 + pcm.size)
        val d = java.io.DataOutputStream(out)
        fun le16(v:Int){ d.writeByte(v and 255); d.writeByte((v ushr 8) and 255) }
        fun le32(v:Int){ d.writeByte(v and 255); d.writeByte((v ushr 8) and 255); d.writeByte((v ushr 16) and 255); d.writeByte((v ushr 24) and 255) }
        d.writeBytes("RIFF"); le32(36 + pcm.size); d.writeBytes("WAVEfmt "); le32(16); le16(1); le16(1)
        le32(48000); le32(96000); le16(2); le16(16); d.writeBytes("data"); le32(pcm.size); d.write(pcm); d.flush()
        return out.toByteArray()
    }

    private fun liveTurnRequest(token:String, pcm:ByteArray): Pair<String,String> {
        val boundary = "Vorlen" + System.currentTimeMillis()
        val c = URL(LIVE_TURN_URL).openConnection() as HttpURLConnection
        c.requestMethod="POST"; c.doOutput=true; c.connectTimeout=15000; c.readTimeout=45000
        c.setRequestProperty("x-device-code", DEVICE_CODE); c.setRequestProperty("x-device-token", token)
        c.setRequestProperty("Content-Type","multipart/form-data; boundary=$boundary")
        java.io.DataOutputStream(c.outputStream).use { o ->
            fun field(name:String,value:String){ o.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n") }
            field("request_id", activeRequestId ?: "")
            o.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"turn.wav\"\r\nContent-Type: audio/wav\r\n\r\n")
            o.write(pcm48MonoToWav(pcm)); o.writeBytes("\r\n--$boundary--\r\n")
        }
        val code=c.responseCode; val body=(if(code in 200..299)c.inputStream else c.errorStream).bufferedReader().readText(); c.disconnect()
        if(code !in 200..299) error("Live AI HTTP $code: $body")
        val j=JSONObject(body); return j.optString("heard") to j.optString("reply")
    }

    private fun speakAlexStreaming(text:String, done:(String?)->Unit) {
        lateinit var tts:TextToSpeech
        tts=TextToSpeech(this){ status ->
            if(status!=TextToSpeech.SUCCESS){ done("TTS init failed"); return@TextToSpeech }
            tts.language=Locale.UK
            val file=File(cacheDir,"alex_live.wav")
            tts.setOnUtteranceProgressListener(object:android.speech.tts.UtteranceProgressListener(){
                override fun onStart(id:String?){}
                override fun onError(id:String?){ tts.shutdown(); done("TTS synthesis failed") }
                override fun onDone(id:String?){
                    try {
                        val pcm=wavTo48kMonoPcm(file)
                        val stream=runBlocking { ShellCallAudio.openUplinkStream(this@MainActivity).getOrThrow() }
                        stream.use { var p=0; while(p<pcm.size){ val e=minOf(p+9600,pcm.size); it.write(pcm.copyOfRange(p,e)); p=e } }
                        tts.shutdown(); done(null)
                    } catch(e:Throwable){ tts.shutdown(); done(e.message) }
                }
            })
            if(tts.synthesizeToFile(text,Bundle(),file,"alex-live")!=TextToSpeech.SUCCESS){ tts.shutdown(); done("TTS request failed") }
        }
    }

    private fun wavTo48kMonoPcm(file: File): ByteArray {
        val bytes = file.readBytes()
        require(bytes.size > 44 && String(bytes, 0, 4) == "RIFF") { "TTS did not produce WAV audio" }
        var pos = 12
        var channels = 1
        var rate = 0
        var bits = 16
        var dataOffset = -1
        var dataSize = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = (bytes[pos+4].toInt() and 255) or ((bytes[pos+5].toInt() and 255) shl 8) or ((bytes[pos+6].toInt() and 255) shl 16) or ((bytes[pos+7].toInt() and 255) shl 24)
            if (id == "fmt " && pos + 24 <= bytes.size) {
                channels = (bytes[pos+10].toInt() and 255) or ((bytes[pos+11].toInt() and 255) shl 8)
                rate = (bytes[pos+12].toInt() and 255) or ((bytes[pos+13].toInt() and 255) shl 8) or ((bytes[pos+14].toInt() and 255) shl 16) or ((bytes[pos+15].toInt() and 255) shl 24)
                bits = (bytes[pos+22].toInt() and 255) or ((bytes[pos+23].toInt() and 255) shl 8)
            } else if (id == "data") { dataOffset = pos + 8; dataSize = minOf(size, bytes.size - dataOffset); break }
            pos += 8 + size + (size and 1)
        }
        require(dataOffset >= 0 && rate > 0 && bits == 16 && channels in 1..2) { "Unsupported TTS WAV: rate=$rate channels=$channels bits=$bits" }
        val frames = dataSize / (2 * channels)
        val src = ShortArray(frames)
        var p = dataOffset
        for (i in 0 until frames) {
            var sum = 0
            repeat(channels) { sum += (((bytes[p].toInt() and 255) or (bytes[p+1].toInt() shl 8)).toShort().toInt()); p += 2 }
            src[i] = (sum / channels).toShort()
        }
        val outFrames = ((frames.toLong() * 48000L) / rate).toInt().coerceAtMost(48000 * 15)
        val out = ByteArray(outFrames * 2)
        for (i in 0 until outFrames) {
            val x = i.toDouble() * rate / 48000.0
            val a = x.toInt().coerceIn(0, frames - 1)
            val b = (a + 1).coerceAtMost(frames - 1)
            val frac = x - a
            val v = (src[a] * (1.0 - frac) + src[b] * frac).toInt().coerceIn(-32768, 32767)
            out[i*2] = (v and 255).toByte(); out[i*2+1] = ((v shr 8) and 255).toByte()
        }
        return out
    }

    override fun onDestroy() {
        // The foreground service owns an approved session across Activity/screen lifecycle.
        polling = false
        pollingIo.shutdownNow()
        outboundIo.shutdownNow()
        proofPlayer?.release()
        proofPlayer = null
        super.onDestroy()
    }

    private fun testGateway(token: String, remoteState: TextView) {
        remoteState.text = "Remote gateway: connecting..."
        pollingIo.execute {
            try {
                val response = gatewayGet(token)
                runOnUiThread { remoteState.text = "Remote gateway: connected" }
                if (!polling) { polling = true; pollGateway(token, remoteState, response) }
            } catch (e: Exception) {
                runOnUiThread { remoteState.text = "Remote gateway: " + (e.message ?: "connection failed") }
            }
        }
    }

    private fun pollGateway(token: String, remoteState: TextView, first: JSONObject? = null) {
        var current = first
        while (polling && !Thread.currentThread().isInterrupted) {
            try {
                val body = current ?: gatewayGet(token); current = null
                val command = body.optJSONObject("command")
                if (command != null && command.optString("action") == "hangup") {
                    val id = command.optString("id")
                    val result = endSimCall()
                    gatewayAck(token, id, if (result.success) "completed" else "failed", "command", if (result.success) null else result.message)
                    runOnUiThread { remoteState.text = if (result.success) "Remote gateway: call ended" else "Remote gateway: hang-up failed" }
                } else if (command != null && command.optString("action") == "call") {
                    val id = command.optString("id"); val phone = command.optString("phone_number")
                    if (validNumber(phone)) {
                        if (sessionApproved) {
                            activeRequestId = id
                            sawOffHook = lastCallState == TelephonyManager.CALL_STATE_OFFHOOK
                            val result = placeSimCall(phone)
                            if (!result.success) { activeRequestId = null; sawOffHook = false }
                            gatewayAck(token, id, if (result.success) "claimed" else "failed", "request", if (result.success) null else result.message)
                            if (result.success) sendEvent("call_requested", if (sawOffHook) "active" else "dialing", id)
                            runOnUiThread { remoteState.text = if (result.success) "Remote gateway: call requested" else "Remote gateway: call failed" }
                        } else {
                            runOnUiThread { remoteState.text = "Remote gateway: session approval required"; showCallApproval(phone, id) }
                        }
                    }
                } else runOnUiThread { remoteState.text = "Remote gateway: connected" }
                Thread.sleep(5000)
            } catch (e: InterruptedException) { Thread.currentThread().interrupt(); break }
            catch (e: Exception) {
                runOnUiThread { remoteState.text = "Remote gateway: retrying" }
                try { Thread.sleep(10000) } catch (_: InterruptedException) { break }
            }
        }
    }

    private fun gatewayGet(token: String): JSONObject {
        val c = URL(GATEWAY_URL).openConnection() as HttpURLConnection
        c.requestMethod = "GET"; c.connectTimeout = 10000; c.readTimeout = 10000
        c.setRequestProperty("x-device-code", DEVICE_CODE); c.setRequestProperty("x-device-token", token)
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream).bufferedReader().use { it.readText() }
        c.disconnect()
        if (code !in 200..299) throw IllegalStateException("HTTP " + code)
        return JSONObject(body)
    }

    private fun gatewayAck(token: String, id: String, state: String, kind: String = "request", error: String? = null) {
        val c = URL(GATEWAY_URL).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 10000
        c.readTimeout = 10000
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-device-code", DEVICE_CODE)
        c.setRequestProperty("x-device-token", token)
        c.outputStream.use {
            it.write(
                JSONObject()
                    .put("id", id)
                    .put("status", state)
                    .put("kind", kind)
                    .put("error", error)
                    .toString()
                    .toByteArray()
            )
        }
        val code = c.responseCode
        if (code in 200..299) {
            c.inputStream.close()
        } else {
            c.errorStream?.close()
        }
        c.disconnect()
        if (code !in 200..299) {
            throw IllegalStateException("Ack HTTP " + code)
        }
    }

    private fun sendEvent(eventType: String, callState: String, requestId: String?) {
        val token = getSharedPreferences("gateway", MODE_PRIVATE).getString("device_token", null) ?: return
        if (outboundIo.isShutdown) return
        outboundIo.execute {
            try {
                val c = URL(GATEWAY_URL).openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10000; c.readTimeout = 10000
                c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("x-device-code", DEVICE_CODE); c.setRequestProperty("x-device-token", token)
                val b = JSONObject().put("type","event").put("event_type",eventType).put("call_state",callState).put("request_id",requestId)
                c.outputStream.use { it.write(b.toString().toByteArray()) }
                if (c.responseCode in 200..299) c.inputStream.close() else c.errorStream?.close()
                c.disconnect()
            } catch (_: Exception) {}
        }
    }

    private fun completeActiveRequest(requestId: String) {
        val token = getSharedPreferences("gateway", MODE_PRIVATE).getString("device_token", null) ?: return
        if (outboundIo.isShutdown) return
        outboundIo.execute { try { gatewayAck(token, requestId, "completed") } catch (_: Exception) {} }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleApprovedIntent(intent, findViewById(R.id.number), findViewById(R.id.status))
    }

    private fun handleApprovedIntent(intent: Intent, numberView: EditText, status: TextView) {
        if (intent.action != ACTION_APPROVE_CALL) return
        val phone = intent.getStringExtra(EXTRA_PHONE).orEmpty()
        numberView.setText(phone)
        val commandId = intent.getStringExtra(EXTRA_COMMAND_ID)
        activeRequestId = commandId
        sawOffHook = lastCallState == TelephonyManager.CALL_STATE_OFFHOOK
        val result = executeApprovedCommand("call", phone)
        if (!result.success) { activeRequestId = null; sawOffHook = false }
        else sendEvent("call_requested", if (sawOffHook) "active" else "dialing", commandId)
        status.text = result.message
        val token = getSharedPreferences("gateway", MODE_PRIVATE).getString("device_token", null)
        if (!commandId.isNullOrBlank() && !token.isNullOrBlank()) outboundIo.execute {
            try { gatewayAck(token, commandId, if (result.success) "claimed" else "failed") } catch (_: Exception) {}
        }
        intent.action = null
    }

    private fun createApprovalChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            APPROVAL_CHANNEL, "Approved call requests", NotificationManager.IMPORTANCE_HIGH
        ))
    }

    // The network consumer will call this after receiving a server-side approved request.
    // It never dials from the network callback: the user must tap the notification action.
    private fun showCallApproval(phone: String, commandId: String) {
        if (!validNumber(phone)) return
        val approve = Intent(this, MainActivity::class.java).apply {
            action = ACTION_APPROVE_CALL
            putExtra(EXTRA_PHONE, phone)
            putExtra(EXTRA_COMMAND_ID, commandId)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this, phone.hashCode(), approve,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, APPROVAL_CHANNEL)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("Vorlen call request")
            .setContentText("Call $phone")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(android.R.drawable.sym_action_call, "Approve & call", pending)
            .build()
        if (android.os.Build.VERSION.SDK_INT < 33 ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            getSystemService(NotificationManager::class.java).notify(phone.hashCode(), notification)
        }
    }

    data class CallResult(val success: Boolean, val message: String)

    private fun placeSimCall(number: String): CallResult {
        if (!validNumber(number)) return CallResult(false, "Enter a valid non-emergency number")
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            return CallResult(false, "Call permission required")
        }
        return try {
            val extras = Bundle().apply { putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, false) }
            getSystemService(TelecomManager::class.java).placeCall(Uri.parse("tel:$number"), extras)
            CallResult(true, "Call requested")
        } catch (e: Exception) {
            CallResult(false, "Call failed: ${e.message}")
        }
    }

    private fun endSimCall(): CallResult {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
            return CallResult(false, "Phone-control permission required")
        }
        return try {
            @Suppress("DEPRECATION")
            val ended = getSystemService(TelecomManager::class.java).endCall()
            CallResult(ended, if (ended) "Call ended" else "No active call could be ended")
        } catch (e: Exception) {
            CallResult(false, "Hang-up failed: ${e.message}")
        }
    }


    // Entry point used by an approved command consumer. It deliberately delegates
    // to the same verified telephony implementation as the on-screen button.
    private fun executeApprovedCommand(action: String, phoneNumber: String?): CallResult {
        return when (action.lowercase()) {
            "call" -> {
                if (phoneNumber.isNullOrBlank()) CallResult(false, "Phone number required")
                else placeSimCall(phoneNumber)
            }
            "hangup" -> endSimCall()
            else -> CallResult(false, "Unsupported command")
        }
    }

    companion object {
        private const val ACTION_APPROVE_CALL = "com.vorlen.callgateway.lab.APPROVE_CALL"
        private const val EXTRA_PHONE = "phone_number"
        private const val EXTRA_COMMAND_ID = "command_id"
        private const val DEVICE_CODE = "s24fe-digital"
        private const val GATEWAY_URL = "https://mzkaodoruhklzluikagy.supabase.co/functions/v1/vorlen-call-device"
        private const val LIVE_TURN_URL = "https://mzkaodoruhklzluikagy.supabase.co/functions/v1/vorlen-live-turn"
        private const val APPROVAL_CHANNEL = "approved_calls"
    }

    private fun validNumber(n: String): Boolean {
        if (!n.matches(Regex("^\\+?[0-9]{7,15}$"))) return false
        val digits = n.filter(Char::isDigit)
        return digits !in setOf("999","112","911","000")
    }
}