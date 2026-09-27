package com.vorlen.callgateway.lab

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlin.math.abs

object CallAudioDiagnostics {
    data class Result(
        val source: String,
        val initialized: Boolean,
        val samples: Int,
        val peak: Int,
        val error: String? = null,
    ) {
        val hasSignal: Boolean get() = initialized && samples > 0 && peak > 8
    }

    fun microphone(context: Context): Result =
        capture(context, MediaRecorder.AudioSource.MIC, "MIC")

    fun protectedVoiceCall(context: Context): Result =
        capture(context, MediaRecorder.AudioSource.VOICE_CALL, "VOICE_CALL")

    private fun capture(context: Context, source: Int, label: String): Result {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return Result(label, false, 0, 0, "RECORD_AUDIO permission not granted")
        }
        val sampleRate = 16_000
        val min = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (min <= 0) return Result(label, false, 0, 0, "No supported input buffer")
        val bufferSize = maxOf(min * 2, 4096)
        var record: AudioRecord? = null
        return try {
            record = AudioRecord(
                source,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                return Result(label, false, 0, 0, "AudioRecord did not initialize")
            }
            val data = ShortArray(bufferSize / 2)
            record.startRecording()
            var total = 0
            var peak = 0
            val deadline = System.currentTimeMillis() + 1_500
            while (System.currentTimeMillis() < deadline) {
                val n = record.read(data, 0, data.size, AudioRecord.READ_BLOCKING)
                if (n < 0) return Result(label, true, total, peak, "read() failed: $n")
                total += n
                for (i in 0 until n) peak = maxOf(peak, abs(data[i].toInt()))
            }
            Result(label, true, total, peak)
        } catch (t: Throwable) {
            Result(label, false, 0, 0, "${t.javaClass.simpleName}: ${t.message ?: "blocked"}")
        } finally {
            runCatching { record?.stop() }
            runCatching { record?.release() }
        }
    }
}
