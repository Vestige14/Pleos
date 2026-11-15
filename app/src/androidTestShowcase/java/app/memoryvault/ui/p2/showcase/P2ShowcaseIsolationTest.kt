package app.memoryvault.ui.p2.showcase

import android.app.Activity
import android.app.Application
import android.app.job.JobScheduler
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import app.memoryvault.R
import app.memoryvault.data.DayDraft
import app.memoryvault.domain.DriveState
import app.memoryvault.memory.MemoryAssociationRuntime
import app.memoryvault.sync.MemoryLinkProgress
import app.memoryvault.sync.MemoryLinkRuntime
import app.memoryvault.ui.DayDraftState
import app.memoryvault.ui.FixtureUi
import app.memoryvault.ui.MemoryVaultScreen
import app.memoryvault.ui.MemoryVaultTheme
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Run only against app.memoryvault.showcase; no product package data is opened. */
class P2ShowcaseIsolationTest {
    @get:Rule val compose = createAndroidComposeRule<ShowcaseHostActivity>()

    @Test fun isolatedManifestAndActualReconnectRemainDormant() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val test = instrumentation.context
        assertPackages(target, test)
        assertDormant()
        val scheduler = requireNotNull(target.getSystemService(JobScheduler::class.java))
        val beforeJobs = pendingJobs(scheduler)
        assertTrue("A fresh showcase target must have no scheduled work", beforeJobs.isEmpty())
        val beforeFiles = targetFiles(target)
        assertNoProductFiles(beforeFiles)

        val ui = FixtureUi(DriveState.PARKED)
        ui.day.value = DayDraftState.Ready(DayDraft("2026-11-08", emptyList()))
        val context = ShowcaseContext(target)
        val launches = ActivityLaunchProbe(compose.activity)
        compose.activity.application.registerActivityLifecycleCallbacks(launches)
        try {
            compose.setContent {
                CompositionLocalProvider(LocalContext provides context) {
                    MemoryVaultTheme { MemoryVaultScreen(ui, onRequestPermissions = {}) }
                }
            }
            compose.waitForIdle()
            assertTrue(compose.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
            assertEquals(DriveState.PARKED, ui.driveSnapshot.value.state)
            assertEquals("Composition must not request an external activity", 0,
                context.launchAttempts.get())
            assertEquals("Composition must not create another target activity", 0,
                launches.created.get())
            assertEquals("The host must be foreground before reconnect", 0,
                launches.hostPaused.get())
            // This is the production button and callback, not a fixture substitute.
            compose.onNodeWithText(target.getString(R.string.ai_reconcile))
                .performScrollTo().performClick()
            compose.waitForIdle()

            // This is a bounded observation window, not proof that reconcile's IO launch ended.
            val observedJobs = mutableSetOf<Pair<Int, String>>()
            var changedFilesObserved = false
            runBlocking {
                repeat(40) {
                    observedJobs.addAll(pendingJobs(scheduler))
                    if (targetFiles(target) != beforeFiles) changedFilesObserved = true
                    delay(50)
                }
            }
            assertTrue("No job may appear during the bounded watch", observedJobs.isEmpty())
            assertFalse("No showcase package file may appear during the bounded watch",
                changedFilesObserved)
            assertDormant()
            val afterFiles = targetFiles(target)
            assertNoProductFiles(afterFiles)
            assertEquals("Reconnect must not create package storage", beforeFiles, afterFiles)
            assertEquals("Reconnect must not enqueue a job", beforeJobs, pendingJobs(scheduler))
            assertEquals("A blocked launch is a failed boundary, not a successful no-op", 0,
                context.launchAttempts.get())
            assertEquals("No other target activity may start", 0, launches.created.get())
            assertEquals("The host must remain foreground", 0, launches.hostPaused.get())
        } finally {
            // AndroidX Test's EmptyActivity may be used later by rule teardown in the test APK.
            // This probe covers product composition and the actual reconnect click only.
            compose.activity.application.unregisterActivityLifecycleCallbacks(launches)
        }
    }

    @Suppress("DEPRECATION")
    private fun assertPackages(target: Context, test: Context) {
        assertEquals(TARGET_PACKAGE, target.packageName)
        assertEquals(TEST_PACKAGE, test.packageName)
        assertTrue(target.applicationContext is ShowcaseApplication)
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_RECEIVERS or
            PackageManager.GET_SERVICES or PackageManager.GET_PROVIDERS or
            PackageManager.GET_PERMISSIONS or PackageManager.GET_INSTRUMENTATION
        val manager = target.packageManager
        val appInfo = manager.getPackageInfo(TARGET_PACKAGE, flags)
        val testInfo = test.packageManager.getPackageInfo(TEST_PACKAGE, flags)
        assertEquals(ShowcaseApplication::class.java.name, appInfo.applicationInfo?.className)
        assertNull("Showcase must not share the product UID", appInfo.sharedUserId)
        assertNull("Test APK must not declare a shared UID", testInfo.sharedUserId)
        assertTrue(target.applicationInfo.dataDir.contains(TARGET_PACKAGE))
        assertEquals(setOf(ShowcaseHostActivity::class.java.name),
            appInfo.activities.orEmpty().map { it.name }.toSet())
        assertTrue("The host must not be exported", appInfo.activities.orEmpty().none { it.exported })
        assertTrue("No target provider is allowed", appInfo.providers.isNullOrEmpty())
        assertTrue("No target service is allowed", appInfo.services.isNullOrEmpty())
        assertTrue("No target receiver is allowed", appInfo.receivers.isNullOrEmpty())
        assertTrue("No target permission is allowed", appInfo.requestedPermissions.isNullOrEmpty())
        assertTrue("No test provider is allowed", testInfo.providers.isNullOrEmpty())
        assertTrue("No test service is allowed", testInfo.services.isNullOrEmpty())
        assertTrue("No test receiver is allowed", testInfo.receivers.isNullOrEmpty())
        val testActivities = testInfo.activities.orEmpty()
        assertEquals("Only the resolved AndroidX Test teardown activity is allowed",
            setOf(TEST_EMPTY_ACTIVITY), testActivities.map { it.name }.toSet())
        assertTrue("The test-only EmptyActivity must keep its resolved exported contract",
            testActivities.single().exported)
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(TEST_PACKAGE)
        assertTrue("The test APK must have no launcher entry",
            test.packageManager.queryIntentActivities(launcher, 0).isEmpty())
        val testPermissions = testInfo.requestedPermissions.orEmpty().toSet()
        assertTrue("The test APK must declare no permissions", testPermissions.isEmpty())
        val runners = testInfo.instrumentation.orEmpty()
        assertEquals(1, runners.size)
        assertEquals(ShowcaseIsolationRunner::class.java.name, runners.single().name)
        assertEquals(TARGET_PACKAGE, runners.single().targetPackage)
        assertFalse("Target and test package IDs must differ", target.packageName == test.packageName)
    }

    private fun assertDormant() {
        assertNull("Memory association runtime must not start", MemoryAssociationRuntime.instance.value)
        assertEquals("Link runtime must not load product jobs", MemoryLinkProgress(),
            MemoryLinkRuntime.progress.value)
    }

    private fun pendingJobs(scheduler: JobScheduler): Set<Pair<Int, String>> =
        scheduler.allPendingJobs.map { it.id to it.service.className }.toSet()

    private fun targetFiles(target: Context): Map<String, Set<String>> {
        val data = File(target.applicationInfo.dataDir)
        fun entries(name: String) = File(data, name).list()?.toSet().orEmpty()
        return mapOf(
            "databases" to target.databaseList().toSet(),
            "shared_prefs" to entries("shared_prefs"),
            "files" to target.fileList().toSet(),
            "no_backup" to entries("no_backup"),
        )
    }

    private fun assertNoProductFiles(files: Map<String, Set<String>>) {
        val names = files.values.flatten()
        assertTrue("Showcase must not create product DB, WorkManager DB or settings",
            names.none { name ->
                name.contains("memory-vault", ignoreCase = true) ||
                    name.contains("share-selections", ignoreCase = true) ||
                    name.contains("androidx.work", ignoreCase = true) ||
                    name.contains("voice_setup", ignoreCase = true) ||
                    name.contains("memory_link", ignoreCase = true)
            })
    }

    private companion object {
        const val TARGET_PACKAGE = "app.memoryvault.showcase"
        const val TEST_PACKAGE = "app.memoryvault.showcase.test"
        const val TEST_EMPTY_ACTIVITY =
            "androidx.test.core.app.InstrumentationActivityInvoker\$EmptyActivity"
    }
}

/** A memory-only context. Launch attempts are counted even though forwarding is blocked. */
internal class ShowcaseContext(base: Context) : ContextWrapper(base) {
    val launchAttempts = AtomicInteger()
    private val preferences = ConcurrentHashMap<String, SharedPreferences>()

    override fun getApplicationContext(): Context = this

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
        preferences.computeIfAbsent(name.orEmpty()) { MemoryPreferences() }

    override fun startActivity(intent: Intent) { launchAttempts.incrementAndGet() }
    override fun startActivity(intent: Intent, options: Bundle?) { launchAttempts.incrementAndGet() }
    override fun startActivities(intents: Array<out Intent>) { launchAttempts.incrementAndGet() }
    override fun startActivities(intents: Array<out Intent>, options: Bundle?) {
        launchAttempts.incrementAndGet()
    }
}

private class ActivityLaunchProbe(private val host: Activity) : Application.ActivityLifecycleCallbacks {
    val created = AtomicInteger()
    val hostPaused = AtomicInteger()
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        created.incrementAndGet()
    }
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) {
        if (activity === host) hostPaused.incrementAndGet()
    }
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

/** The receipt composable can read this interface without touching a package preference file. */
private class MemoryPreferences : SharedPreferences {
    private val values = linkedMapOf<String, Any>()

    override fun getAll(): MutableMap<String, *> = synchronized(values) { values.toMutableMap() }
    override fun getString(key: String?, defValue: String?): String? =
        synchronized(values) { values[key] as? String ?: defValue }
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        synchronized(values) { (values[key] as? Set<*>)?.filterIsInstance<String>()?.toMutableSet() ?: defValues }
    override fun getInt(key: String?, defValue: Int): Int =
        synchronized(values) { values[key] as? Int ?: defValue }
    override fun getLong(key: String?, defValue: Long): Long =
        synchronized(values) { values[key] as? Long ?: defValue }
    override fun getFloat(key: String?, defValue: Float): Float =
        synchronized(values) { values[key] as? Float ?: defValue }
    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        synchronized(values) { values[key] as? Boolean ?: defValue }
    override fun contains(key: String?): Boolean = synchronized(values) { values.containsKey(key) }
    override fun edit(): SharedPreferences.Editor = MemoryEditor()
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private inner class MemoryEditor : SharedPreferences.Editor {
        private val changes = linkedMapOf<String, Any?>()
        private var clearFirst = false
        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
            changes[requireNotNull(key)] = value
        }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply {
            changes[requireNotNull(key)] = values?.toSet()
        }
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
            changes[requireNotNull(key)] = value
        }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
            changes[requireNotNull(key)] = value
        }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
            changes[requireNotNull(key)] = value
        }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
            changes[requireNotNull(key)] = value
        }
        override fun remove(key: String?): SharedPreferences.Editor = apply {
            changes[requireNotNull(key)] = null
        }
        override fun clear(): SharedPreferences.Editor = apply { clearFirst = true }
        override fun commit(): Boolean {
            synchronized(values) {
                if (clearFirst) values.clear()
                changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            }
            return true
        }
        override fun apply() { commit() }
    }
}
