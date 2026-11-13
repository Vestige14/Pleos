package app.memoryvault

import android.app.Application

class MemoryVaultApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
    override fun onCreate() {
        super.onCreate()
        container.start()
    }
}
