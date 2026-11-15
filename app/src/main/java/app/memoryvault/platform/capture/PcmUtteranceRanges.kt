package app.memoryvault.platform.capture

import kotlin.math.sqrt

/** Conservative boundaries for the SDK's single-utterance external-PCM recognizer.
 * All PCM16LE/16kHz/mono bytes belong to exactly one range; the stored audio is untouched.
 * This is a quiet-pause heuristic, not a claim to detect speech in every noise environment.
 */
internal object PcmUtteranceRanges {
    fun split(pcm: ByteArray): List<IntRange> {
        require(pcm.isNotEmpty() && pcm.size <= 480_000 && pcm.size % 2 == 0)
        val frameBytes = 640 // 20 ms
        val levels = (pcm.indices step frameBytes).map { start ->
            val end = minOf(start + frameBytes, pcm.size)
            var sum = 0.0
            for (i in start until end step 2) {
                val sample = ((pcm[i].toInt() and 255) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
                sum += sample.toDouble() * sample
            }
            sqrt(sum / ((end - start) / 2))
        }
        val threshold = (levels.sorted()[levels.size / 5] * 2.5).coerceIn(100.0, 300.0)
        var remainingActive = levels.count { it > threshold }
        var activeSinceCut = 0
        var quietStart = -1
        val cuts = mutableListOf(0)
        levels.forEachIndexed { index, level ->
            if (level <= threshold) {
                if (quietStart == -1) quietStart = index
            } else {
                if (quietStart >= 0 && index - quietStart >= 20 && activeSinceCut >= 15 && remainingActive >= 15) {
                    cuts += ((quietStart + index) / 2) * frameBytes
                    require(cuts.size <= 16) { "Too many utterances" }
                    activeSinceCut = 0
                }
                quietStart = -1
                activeSinceCut++
                remainingActive--
            }
        }
        cuts += pcm.size
        return cuts.zipWithNext { from, until -> from until until }
    }
}
