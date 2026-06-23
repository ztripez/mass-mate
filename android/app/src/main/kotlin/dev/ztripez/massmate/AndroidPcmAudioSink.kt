package dev.ztripez.massmate

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build

/** Android `AudioTrack` stream-mode sink factory for negotiated PCM16 Sendspin frames. */
class AndroidPcmAudioSinkFactory : SendspinAudioSinkFactory {
    override fun create(format: SendspinPcmAudioFormat): SendspinAudioSink = AndroidPcmAudioTrackSink(format)
}

/**
 * Owns one stream-mode [AudioTrack] instance for a negotiated PCM stream.
 *
 * @param format Negotiated PCM sample rate and channel count used to configure AudioTrack.
 */
class AndroidPcmAudioTrackSink(
    private val format: SendspinPcmAudioFormat,
) : SendspinAudioSink {
    private val channelMask = when (format.channels) {
        1 -> AudioFormat.CHANNEL_OUT_MONO
        2 -> AudioFormat.CHANNEL_OUT_STEREO
        else -> throw audioError("Unsupported PCM channel count for Android AudioTrack.", mapOf("channels" to format.channels))
    }
    private val bufferSizeBytes = minimumBufferSizeBytes(format.sampleRateHz, channelMask)
    private val audioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(format.sampleRateHz)
                .setChannelMask(channelMask)
                .build(),
        )
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(bufferSizeBytes)
        .build()

    init {
        if (audioTrack.state != AudioTrack.STATE_INITIALIZED) {
            throw audioError("Android AudioTrack did not initialize for Sendspin PCM output.")
        }
    }

    override fun start() {
        try {
            audioTrack.play()
        } catch (error: IllegalStateException) {
            throw audioError("Android AudioTrack failed to start.", mapOf("message" to error.message))
        }
    }

    override fun write(bytes: ByteArray): Int {
        val accepted = audioTrack.write(bytes, 0, bytes.size, AudioTrack.WRITE_NON_BLOCKING)
        if (accepted < 0) {
            throw audioError("Android AudioTrack rejected a PCM write.", mapOf("result" to accepted))
        }
        return accepted
    }

    override fun flush() {
        try {
            audioTrack.pause()
            audioTrack.flush()
            audioTrack.play()
        } catch (error: IllegalStateException) {
            throw audioError("Android AudioTrack failed to flush.", mapOf("message" to error.message))
        }
    }

    override fun stop() {
        try {
            audioTrack.pause()
            audioTrack.flush()
        } catch (error: IllegalStateException) {
            throw audioError("Android AudioTrack failed to stop.", mapOf("message" to error.message))
        }
    }

    override fun release() {
        audioTrack.release()
    }

    override fun underrunCount(): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        audioTrack.underrunCount.toLong()
    } else {
        0L
    }

    private fun minimumBufferSizeBytes(sampleRateHz: Int, channelMask: Int): Int {
        val minimum = AudioTrack.getMinBufferSize(sampleRateHz, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) {
            throw audioError(
                "Android AudioTrack minimum buffer size is invalid.",
                mapOf("result" to minimum, "sampleRateHz" to sampleRateHz, "channels" to format.channels),
            )
        }
        val alignment = (format.bytesPerAudioFrame - minimum % format.bytesPerAudioFrame) % format.bytesPerAudioFrame
        val frameAlignedMinimum = checkedIntAdd(minimum, alignment, "audioTrackAlignedBufferSizeBytes")
        val minimumFrameBuffer = checkedIntMultiply(
            format.bytesPerAudioFrame,
            MINIMUM_AUDIO_FRAMES,
            "audioTrackMinimumFrameBufferBytes",
        )
        return maxOf(frameAlignedMinimum, minimumFrameBuffer)
    }

    private fun checkedIntAdd(left: Int, right: Int, label: String): Int = try {
        Math.addExact(left, right)
    } catch (error: ArithmeticException) {
        throw audioError("Android AudioTrack overflowed `$label` calculation.", mapOf("left" to left, "right" to right))
    }

    private fun checkedIntMultiply(left: Int, right: Int, label: String): Int = try {
        Math.multiplyExact(left, right)
    } catch (error: ArithmeticException) {
        throw audioError("Android AudioTrack overflowed `$label` calculation.", mapOf("left" to left, "right" to right))
    }
}

private const val MINIMUM_AUDIO_FRAMES = 2048

private fun audioError(message: String, details: Map<String, Any?>? = null): SendspinConnectionException =
    SendspinConnectionException(LocalPlayerEnvelope.LOCAL_PLAYER_AUDIO_ERROR, message, details)
