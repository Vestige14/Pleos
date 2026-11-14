package app.memoryvault.share

import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedPngOutputTest {
    @Test fun writingExactlyTheLimitKeepsAllBytes() {
        val output = BoundedPngOutput(4)
        output.write(byteArrayOf(10, 20))
        output.write(30)
        output.write(byteArrayOf(0, 40, 0), 1, 1)
        assertArrayEquals(byteArrayOf(10, 20, 30, 40), output.toByteArray())
        assertFalse(output.limitReached)
    }

    // A missing bulk-write guard can grow the encoded buffer past its reserved memory budget.
    @Test fun bulkWritePastLimitFailsWithoutSilentlyWritingAPrefix() {
        val output = BoundedPngOutput(4)
        output.write(byteArrayOf(10, 20))
        assertThrows(IOException::class.java) { output.write(byteArrayOf(30, 40, 50)) }
        assertTrue(output.limitReached)
        assertEquals(2, output.size())
    }

    // A missing single-byte override can bypass the same cap used by the bulk writer.
    @Test fun singleBytePastLimitFails() {
        val output = BoundedPngOutput(1)
        output.write(10)
        assertThrows(IOException::class.java) { output.write(20) }
        assertTrue(output.limitReached)
        assertEquals(1, output.size())
    }
}
