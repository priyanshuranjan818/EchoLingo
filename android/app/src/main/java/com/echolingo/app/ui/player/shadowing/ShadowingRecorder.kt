package com.echolingo.app.ui.player.shadowing

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Records user speech via MediaRecorder → M4A file.
 * - Max 8 seconds (MediaRecorder hard limit)
 * - 16kHz, 64kbps AAC (Groq Whisper sweet-spot)
 * - Call startRecording() → stopRecording() → cleanup() lifecycle.
 */
class ShadowingRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    /** Returns the output file path — NOT yet complete until stopRecording() is called. */
    fun startRecording(): File {
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
            setMaxDuration(8_000)          // 8 s hard cap
            setOutputFile(outputFile!!.absolutePath)
            prepare()
            start()
        }

        return outputFile!!
    }

    /** Stops recording and returns the completed file, or null if not recording. */
    fun stopRecording(): File? {
        return try {
            recorder?.stop()
            outputFile
        } catch (e: RuntimeException) {
            // stop() throws if called before any data was recorded
            outputFile?.delete()
            null
        } finally {
            recorder?.release()
            recorder = null
        }
    }

    fun isRecording(): Boolean = recorder != null

    /** Delete the temp file when done. */
    fun cleanup() {
        recorder?.release()
        recorder = null
        outputFile?.delete()
        outputFile = null
    }
}
