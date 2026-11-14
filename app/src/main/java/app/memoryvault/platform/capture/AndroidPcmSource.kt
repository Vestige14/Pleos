package app.memoryvault.platform.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/** The only microphone owner in the PCM path. Construction is inert until start(). */
class AndroidPcmSource(context: Context) : PcmSource {
    private val appContext = context.applicationContext
    private val lock = Any()
    @Volatile private var stopped = false
    @Volatile private var recorder: AudioRecord? = null
    private val samples = ShortArray(1_600)

    override fun start() = synchronized(lock) {
        check(recorder == null && !stopped) { "Microphone session already used" }
        check(appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "Microphone permission missing" }
        val minimum = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "PCM microphone format unsupported" }
        val acquired = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16_000)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minimum, 6_400)).build()
        recorder = acquired
        check(acquired.state == AudioRecord.STATE_INITIALIZED && acquired.sampleRate == 16_000 &&
            acquired.channelCount == 1 && acquired.audioFormat == AudioFormat.ENCODING_PCM_16BIT) { "PCM microphone unavailable" }
        acquired.startRecording()
        check(acquired.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start" }
    }

    override fun read(buffer: ByteArray): Int {
        if (stopped) return -1
        val current = checkNotNull(recorder)
        val read = current.read(samples, 0, minOf(samples.size, buffer.size / 2), AudioRecord.READ_BLOCKING)
        if (read < 0) {
            if (stopped) return -1
            error("Microphone read failed")
        }
        // Explicit little endian serialization; only the actually returned sample count is used.
        repeat(read) { i -> buffer[2 * i] = samples[i].toByte(); buffer[2 * i + 1] = (samples[i].toInt() shr 8).toByte() }
        return read * 2
    }

    override fun stop() = synchronized(lock) {
        if (!stopped) {
            stopped = true
            recorder?.takeIf { it.recordingState == AudioRecord.RECORDSTATE_RECORDING }?.stop()
        }
    }

    override fun close() = synchronized(lock) {
        val current = recorder
        try { stop() } finally { recorder = null; current?.release() }
    }
}
