package app.memoryvault.platform

/** Narrow asynchronous SDK boundary; error payloads never cross it. */
internal interface SpeechSdk {
    interface Initialization { fun ready(); fun failed() }
    interface Listener {
        fun started()
        fun updated(text: String, final: Boolean)
        fun ended()
        fun failed()
    }
    fun initialize(callback: Initialization)
    fun setKoreanLocale()
    fun addListener(listener: Listener)
    fun request()
    fun stop()
    fun removeListener(listener: Listener)
    fun release()
}
