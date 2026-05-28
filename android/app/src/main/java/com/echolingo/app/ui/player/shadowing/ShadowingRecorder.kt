package com.echolingo.app.ui.player.shadowing

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Records user speech via MediaRecorder → M4A file.
 * - Max 8 seconds then fires onAutoStop automatically
 * - 16kHz, 64kbps AAC (Groq Whisper sweet-spot)
 */
class ShadowingRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    fun startRecording(onAutoStop: () -> Unit = {}): File {
        outputFile = File(context.cacheDir, "shadow_${System.currentTimeMillis()}.m4a")

        recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        recorder!!.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(16_000)
            setAudioEncodingBitRate(64_000)
            setMaxDuration(8_000)
            setOutputFile(outputFile!!.absolutePath)
            // Auto-fire when 8s max duration reached — no "Done" button needed
            setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    onAutoStop()
                }
            }
            prepare()
            start()
        }

        return outputFile!!
    }

    fun stopRecording(): File? {
        return try {
            recorder?.stop()
            outputFile
        } catch (e: RuntimeException) {
            outputFile?.delete()
            null
        } finally {
            recorder?.release()
            recorder = null
        }
    }

    fun isRecording(): Boolean = recorder != null

    fun cleanup() {
        recorder?.release()
        recorder = null
        outputFile?.delete()
        outputFile = null
    }
}
