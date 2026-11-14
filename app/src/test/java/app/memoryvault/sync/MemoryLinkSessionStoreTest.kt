package app.memoryvault.sync

import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryLinkSessionStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val cipher = AesGcmSessionCipher { SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") }
    @Test fun encryptedFileReopensWithOnlyMaskedMetadataAndRejectsTampering() {
        val dir = temp.newFolder()
        val store = EncryptedLinkSessionFile(dir, cipher, true)
        assertNull(store.load())
        val token = "synthetic_session_" + "a".repeat(32)
        store.replace("http://127.0.0.1:8000", token)
        val raw = dir.listFiles()!!.single().readBytes()
        assertFalse(raw.toString(Charsets.ISO_8859_1).contains(token))
        assertFalse(raw.toString(Charsets.ISO_8859_1).contains("127.0.0.1"))
        val reopened = EncryptedLinkSessionFile(dir, cipher, true)
        assertEquals("http://127.0.0.1:8000", reopened.summary().endpoint)
        assertTrue(reopened.summary().configured)
        assertEquals("Bearer $token", reopened.load()!!.authorization())
        val changed = raw.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        dir.listFiles()!!.single().writeBytes(changed)
        val error = assertThrows(LinkSessionException::class.java) { reopened.load() }
        assertEquals("SESSION_STORAGE", error.message); assertNull(error.cause)
        assertFalse(error.toString().contains(token))
    }
    @Test fun replacementUsesNewTokenAndInvalidInputPreservesThePreviousSession() {
        val store = EncryptedLinkSessionFile(temp.newFolder(), cipher, true)
        store.replace("http://localhost:8000", "a".repeat(48))
        val first = store.load()!!.authorization()
        assertThrows(IllegalArgumentException::class.java) { store.replace("http://192.168.1.2", "b".repeat(48)) }
        assertThrows(IllegalArgumentException::class.java) { store.replace("http://localhost:8000", "short") }
        assertThrows(IllegalArgumentException::class.java) { store.replace("http://localhost:8000", "x".repeat(47) + "\n") }
        assertEquals(first, store.load()!!.authorization())
        store.replace("http://localhost:9000", "b".repeat(48))
        assertEquals("Bearer " + "b".repeat(48), store.load()!!.authorization())
    }
    @Test fun sessionPublicationRechecksGuardAfterEncryptionAndPreservesPreviousFile() {
        val dir = temp.newFolder()
        val first = EncryptedLinkSessionFile(dir, cipher, true)
        first.replace("http://localhost:8000", "a".repeat(48))
        var allowed = true
        val interrupted = EncryptedLinkSessionFile(dir, AesGcmSessionCipher {
            allowed = false; SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        }, true)
        assertThrows(app.memoryvault.sync.ui.LinkSetupBlocked::class.java) {
            interrupted.replace("http://localhost:9000", "b".repeat(48)) { allowed }
        }
        assertEquals("Bearer " + "a".repeat(48), first.load()!!.authorization())
    }
    @Test fun ciphertextUsesDifferentNonceEachTimeAndCannotMoveAcrossKeys() {
        val plain = "synthetic-only".toByteArray()
        val a = cipher.encrypt(plain); val b = cipher.encrypt(plain)
        assertFalse(a.contentEquals(b)); assertArrayEquals(plain, cipher.decrypt(a))
        val other = AesGcmSessionCipher { SecretKeySpec(ByteArray(32) { 7 }, "AES") }
        assertThrows(LinkSessionException::class.java) { other.decrypt(a) }
    }
}
