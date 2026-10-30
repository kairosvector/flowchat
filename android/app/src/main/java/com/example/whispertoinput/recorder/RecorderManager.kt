/*
 * This file is part of Whisper To Input, see <https://github.com/j3soon/whisper-to-input>.
 *
 * Copyright (c) 2023-2025 Yan-Bin Diau, Johnson Sun
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.example.whispertoinput.recorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.whispertoinput.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private const val MEDIA_RECORDER_CONSTRUCTOR_DEPRECATION_API_LEVEL = 31

class RecorderManager(context: Context) {
    companion object {
        fun requiredPermissions() = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }

    private var recorder: MediaRecorder? = null
    private var onUpdateMicrophoneAmplitude: (Int) -> Unit = { }
    private var microphoneAmplitudeUpdateJob: Job? = null
    private val amplitudeReportPeriod: Long
    private val context: Context

    init {
        this.context = context
        this.amplitudeReportPeriod =
            context.resources.getInteger(R.integer.recorder_amplitude_report_period).toLong()
    }

    // Fork change (fork5): returns false instead of throwing.
    // Previously prepare() failure was logged and start() was then
    // called unconditionally, so a failed prepare — or a start() that
    // throws because the microphone is still held (for example right
    // after switching away from another IME) — propagated as an
    // uncaught exception on the main thread and killed the service.
    // On a switch to Whisper that is especially bad: the first
    // onWindowShown auto-starts a recording, so a start failure there
    // used to look exactly like a keyboard that never appeared. Now
    // the caller just leaves the keyboard idle and the user can tap
    // the mic again; nothing on this path can crash the service.
    fun start(context: Context, filename: String, useOggFormat: Boolean = false): Boolean {
        try {
            stop()
        } catch (_: Exception) {
            // Old recorder was already unusable; continue with a fresh one.
        }

        val newRecorder =
            if (Build.VERSION.SDK_INT >= MEDIA_RECORDER_CONSTRUCTOR_DEPRECATION_API_LEVEL) {
                MediaRecorder(context)
            } else {
                MediaRecorder()
            }
        recorder = newRecorder

        val file: File = File(filename)
        if (file.exists()) {
            file.delete()
            Log.e("whisper-input", "File should not exist")
        }

        try {
            newRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                if (useOggFormat) {
                    // Use the OGG and OPUS encoder following the recommendation in NVIDIA Riva
                    // Ref: https://docs.nvidia.com/deeplearning/riva/user-guide/docs/reference/protos/riva_audio.proto.html
                    // This format and encoder is the only one supported by both NVIDIA Riva and MediaRecorder.
                    setOutputFormat(MediaRecorder.OutputFormat.OGG)
                    setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                } else {
                    // Use M4A format for other backends
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                }
                setOutputFile(filename)
                prepare()
                start()
            }
        } catch (e: Exception) {
            // prepare() or start() failed (bad state, mic held by
            // another app/IME, missing permission at the OS level):
            // release, forget the recorder, and report failure instead
            // of crashing the caller.
            Log.e("whisper-input", "Recorder start failed: ${e.javaClass.simpleName}")
            try {
                newRecorder.release()
            } catch (_: Exception) {
                // Already released or unusable; nothing more to do.
            }
            recorder = null
            return false
        }

        // Start a job to periodically report current amplitude
        microphoneAmplitudeUpdateJob?.cancel()
        microphoneAmplitudeUpdateJob = CoroutineScope(Dispatchers.Main).launch {
            while (recorder != null) {
                val amplitude = recorder?.maxAmplitude ?: 0
                onUpdateMicrophoneAmplitude(amplitude)
                delay(amplitudeReportPeriod)
            }
        }
        return true
    }

    // Fork change (fork5): stop() on a MediaRecorder that was never
    // started, was already stopped, or recorded nothing throws
    // IllegalStateException / RuntimeException. Previously that
    // exception escaped from onWindowShown / onWindowHidden /
    // onDestroy — all of which run on an IME switch — and could kill
    // the service at exactly the switch moment. Now stop() never
    // throws: the recorder is always released and forgotten.
    fun stop() {
        val oldRecorder = recorder
        recorder = null
        if (oldRecorder != null) {
            try {
                oldRecorder.stop()
            } catch (_: Exception) {
                // Never started, already stopped, or no data recorded.
            }
            try {
                oldRecorder.release()
            } catch (_: Exception) {
                // Already released.
            }
        }

        microphoneAmplitudeUpdateJob?.cancel()
        microphoneAmplitudeUpdateJob = null
    }

    // Assign onUpdateMicrophoneAmplitude callback
    fun setOnUpdateMicrophoneAmplitude(onUpdateMicrophoneAmplitude: (Int) -> Unit) {
        this.onUpdateMicrophoneAmplitude = onUpdateMicrophoneAmplitude
    }

    // Returns whether all of the permissions are granted.
    fun allPermissionsGranted(context: Context): Boolean {
        for (permission in requiredPermissions()) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    permission
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
        }

        return true
    }
}