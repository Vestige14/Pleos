package app.memoryvault.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.memoryvault.BuildConfig
import app.memoryvault.sync.ui.LinkSetupBlocked
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class LinkSessionException : IllegalStateException("SESSION_STORAGE")
data class LinkSessionSummary(val configured: Boolean, val endpoint: String?)
interface LinkSessionSource { fun load(): LinkSession? }

/** Every encryption has a provider-generated nonce and an authenticated format identifier. */
class AesGcmSessionCipher(private val key: () -> SecretKey) {
    private val aad = "memory-link-session-v1".toByteArray(Charsets.UTF_8)
    fun encrypt(value: ByteArray): ByteArray = guarded {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        require(cipher.iv.size == 12)
        cipher.updateAAD(aad)
        byteArrayOf(1) + cipher.iv + cipher.doFinal(value)
    }
    fun decrypt(value: ByteArray): ByteArray = guarded {
        require(value.size in 30..8192 && value[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, value.copyOfRange(1, 13)))
        cipher.updateAAD(aad)
        cipher.doFinal(value.copyOfRange(13, value.size))
    }
    private inline fun <T> guarded(block: () -> T): T = try { block() } catch (_: Exception) { throw LinkSessionException() }
}

/** Token is only available to the HTTP consumer; UI reads summary metadata, never a saved token. */
class EncryptedLinkSessionFile(private val dir: File, private val cipher: AesGcmSessionCipher,
    private val allowLocalHttp: Boolean) : LinkSessionSource {
    private val file get() = File(dir, "session.enc")
    @Serializable private data class Stored(val endpoint: String, val token: String) {
        override fun toString() = "Stored([redacted])"
    }
    @Synchronized override fun load(): LinkSession? {
        if (!file.exists()) return null
        return try {
            require(file.length() in 30..8192)
            val clear = cipher.decrypt(file.readBytes())
            try {
                val stored = Json.decodeFromString<Stored>(clear.toString(Charsets.UTF_8))
                validate(stored.endpoint, stored.token)
                LinkSession(stored.endpoint, stored.token)
            } finally { clear.fill(0) }
        } catch (_: Exception) { throw LinkSessionException() }
    }
    fun summary(): LinkSessionSummary = load()?.let { LinkSessionSummary(true, it.endpoint) } ?: LinkSessionSummary(false, null)

    @Synchronized fun replace(endpoint: String, token: String, canCommit: () -> Boolean = { true }) {
        validate(endpoint, token)
        if (!canCommit()) throw LinkSetupBlocked()
        var temp: File? = null
        try {
            check(dir.isDirectory || dir.mkdirs())
            val clear = Json.encodeToString(Stored(endpoint, token)).toByteArray(Charsets.UTF_8)
            val encoded = try { cipher.encrypt(clear) } finally { clear.fill(0) }
            temp = File.createTempFile("session-", ".tmp", dir)
            check(temp.setReadable(false, false) && temp.setWritable(false, false) &&
                temp.setReadable(true, true) && temp.setWritable(true, true))
            FileOutputStream(temp).use { it.write(encoded); it.fd.sync() }
            if (!canCommit()) throw LinkSetupBlocked()
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (blocked: LinkSetupBlocked) { throw blocked }
        catch (_: Exception) { throw LinkSessionException() }
        finally { temp?.delete() }
    }
    private fun validate(endpoint: String, token: String) {
        MemoryLinkEndpoint.validate(endpoint, allowLocalHttp)
        require(Regex("[A-Za-z0-9_-]{32,256}").matches(token)) { "INVALID_SESSION" }
    }
}

class MemoryLinkSessionStore(context: Context) : LinkSessionSource {
    companion object {
        private const val ALIAS = "memory-link-session-aes-v1"
        private val keyLock = Any()
        private fun key(): SecretKey = synchronized(keyLock) {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (store.containsAlias(ALIAS)) return@synchronized store.getKey(ALIAS, null) as SecretKey
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
            generator.generateKey()
        }
    }
    // noBackupFilesDir is excluded from Android backup and device transfer; existing app rules also exclude all root/files.
    private val encrypted = EncryptedLinkSessionFile(File(context.applicationContext.noBackupFilesDir, "memory-link"),
        AesGcmSessionCipher(::key), BuildConfig.MEMORY_LINK_LOCAL_HTTP)
    override fun load(): LinkSession? = encrypted.load()
    fun summary() = encrypted.summary()
    fun replace(endpoint: String, token: String, canCommit: () -> Boolean = { true }) = encrypted.replace(endpoint, token, canCommit)
}
