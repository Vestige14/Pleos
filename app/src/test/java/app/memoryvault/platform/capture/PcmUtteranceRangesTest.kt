package app.memoryvault.platform.capture

import org.junit.Assert.*
import org.junit.Test

class PcmUtteranceRangesTest {
    @Test fun splitsOnlyAtInteriorPauseAndPartitionsAllBytesInOrder() {
        val pcm = samples(400, 4000) + samples(600, 0) + samples(400, 4000)
        val ranges = PcmUtteranceRanges.split(pcm)
        assertEquals(listOf(0..22399, 22400..44799), ranges)
        assertArrayEquals(pcm, ranges.flatMap { pcm.slice(it) }.toByteArray())
    }

    @Test fun leadingAndTrailingSilenceDoNotBecomeEmptySpeechRequests() {
        val pcm = samples(1200, 0) + samples(400, 4000) + samples(600, 0) + samples(400, 4000) + samples(1000, 0)
        assertEquals(listOf(0..60799, 60800..115199), PcmUtteranceRanges.split(pcm))
    }

    @Test fun shortPauseContinuousQuietAndVeryShortPcmKeepOneUnchangedBuffer() {
        for (pcm in listOf(samples(400, 4000) + samples(200, 0) + samples(400, 4000),
            samples(1000, 0), samples(1000, 50), samples(1000, 600), byteArrayOf(1, 2))) {
            assertEquals(listOf(0..pcm.lastIndex), PcmUtteranceRanges.split(pcm))
        }
    }

    @Test fun lowBackgroundNoiseStillAllowsAnInteriorPause() {
        val pcm = samples(400, 4000) + samples(600, 100) + samples(400, 4000)
        assertEquals(listOf(0..22399, 22400..44799), PcmUtteranceRanges.split(pcm))
    }

    @Test fun isolatedLeadingClickDoesNotBecomeAnUtterance() {
        val pcm = samples(20, 4000) + samples(1000, 0) + samples(400, 4000)
        assertEquals(listOf(0..45439), PcmUtteranceRanges.split(pcm))
    }

    @Test fun excessiveSegmentsFailInsteadOfDroppingOrRecombiningTheTail() {
        val pcm = (0 until 17).fold(ByteArray(0)) { bytes, i -> bytes + samples(300, 4000) + if (i < 16) samples(400, 0) else ByteArray(0) }
        assertThrows(IllegalArgumentException::class.java) { PcmUtteranceRanges.split(pcm) }
    }

    @Test fun incompleteOrOversizedPcmIsRejected() {
        for (pcm in listOf(ByteArray(0), ByteArray(3), ByteArray(480002))) {
            assertThrows(IllegalArgumentException::class.java) { PcmUtteranceRanges.split(pcm) }
        }
    }

    private fun samples(millis: Int, amplitude: Int) = ByteArray(millis * 32).also { bytes ->
        for (i in bytes.indices step 2) { bytes[i] = amplitude.toByte(); bytes[i + 1] = (amplitude shr 8).toByte() }
    }
}
