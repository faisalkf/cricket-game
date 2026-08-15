package com.example.cricketgame.sound

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * PLACEHOLDER sound effects. This project has no licensed audio asset pipeline yet, so these
 * three short cues (bat-ball impact, boundary, wicket) are procedurally synthesized on-device -
 * plain damped sine tones written straight to PCM and played via AudioTrack - rather than loaded
 * from recorded audio files. They're meant to be functional and satisfying stand-ins, not final
 * production audio: swap for real recorded assets (e.g. via SoundPool) once that pipeline
 * exists, and delete the synthesis code below.
 */
class SoundEffects {
    private val sampleRate = 44100
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Synthesized once up front so playback itself is just a cheap AudioTrack write + play.
    private val impactBuffer = buildImpactTone()
    private val boundaryBuffer = buildBoundaryTone()
    private val wicketBuffer = buildWicketTone()

    /** Bat-ball contact - any legal shot where the bat actually connects. */
    fun playImpact() = play(impactBuffer)

    /** A four or a six. */
    fun playBoundary() = play(boundaryBuffer)

    /** A dismissal. */
    fun playWicket() = play(wicketBuffer)

    /** Stops any pending playback and frees the coroutine scope - call from onDispose. */
    fun release() = scope.cancel()

    private fun play(samples: ShortArray) {
        scope.launch {
            val track = createStaticTrack(samples)
            try {
                track.play()
                delay(durationMsOf(samples) + 60)
            } finally {
                // Always release the native AudioTrack, even if this coroutine gets cancelled
                // (e.g. the match screen is torn down) mid-playback.
                withContext(NonCancellable) {
                    track.stop()
                    track.release()
                }
            }
        }
    }

    private fun durationMsOf(samples: ShortArray): Long = samples.size * 1000L / sampleRate

    private fun createStaticTrack(samples: ShortArray): AudioTrack {
        val bufferSize = maxOf(
            samples.size * 2, // 16-bit mono: 2 bytes/sample
            AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(samples, 0, samples.size)
        return track
    }

    // --- placeholder tone synthesis -----------------------------------------------------

    /** PLACEHOLDER: a short, punchy low thud standing in for bat-ball contact. */
    private fun buildImpactTone(): ShortArray = dampedTone(freqHz = 180.0, durationMs = 90, decay = 14.0)

    /** PLACEHOLDER: a bright ascending two-note chime standing in for a boundary (four/six). */
    private fun buildBoundaryTone(): ShortArray =
        dampedTone(freqHz = 660.0, durationMs = 110, decay = 6.0) +
            dampedTone(freqHz = 880.0, durationMs = 160, decay = 5.0)

    /** PLACEHOLDER: a short descending double-thud standing in for a wicket - low and blunt. */
    private fun buildWicketTone(): ShortArray =
        dampedTone(freqHz = 220.0, durationMs = 90, decay = 10.0) +
            dampedTone(freqHz = 140.0, durationMs = 180, decay = 7.0)

    /** A single sine tone with an exponential-decay envelope - the basic building block for all
     *  three placeholder effects above. */
    private fun dampedTone(freqHz: Double, durationMs: Int, decay: Double, amplitude: Double = 0.7): ShortArray {
        val sampleCount = sampleRate * durationMs / 1000
        return ShortArray(sampleCount) { i ->
            val t = i.toDouble() / sampleRate
            val envelope = exp(-decay * t)
            val sample = amplitude * envelope * sin(2.0 * PI * freqHz * t)
            (sample * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }
}
