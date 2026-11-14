package app.memoryvault.memory

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.runner.Description
import org.junit.runner.Result
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener

/** Bounded scalar metadata only; no Activity retained, no UI synchronization or Compose idle calls. */
class Stage7LifecycleObserver : RunListener(), Application.ActivityLifecycleCallbacks {
    private val events = java.util.ArrayDeque<String>()
    private fun note(a: Activity, state: String) = synchronized(events) {
        if (events.size == 24) events.removeFirst()
        events.addLast("${SystemClock.elapsedRealtime()}:${a.javaClass.simpleName}:${System.identityHashCode(a)}:$state")
    }
    override fun testRunStarted(d: Description) {
        (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application).registerActivityLifecycleCallbacks(this)
    }
    override fun testFailure(f: Failure) {
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("stage7_failed_method", f.description.methodName)
            putString("stage7_lifecycle", synchronized(events) { events.joinToString("|") })
        })
    }
    override fun testRunFinished(r: Result) {
        (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application).unregisterActivityLifecycleCallbacks(this)
    }
    override fun onActivityCreated(a: Activity,b: Bundle?) { note(a,"created") }
    override fun onActivityStarted(a: Activity) { note(a,"started") }
    override fun onActivityResumed(a: Activity) { note(a,"resumed") }
    override fun onActivityPaused(a: Activity) { note(a,"paused") }
    override fun onActivityStopped(a: Activity) { note(a,"stopped") }
    override fun onActivityDestroyed(a: Activity) { note(a,"destroyed") }
    override fun onActivitySaveInstanceState(a: Activity,b: Bundle) { note(a,"save") }
}
