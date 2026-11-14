package app.memoryvault.memory

import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import app.memoryvault.MemoryVaultApp
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertTrue
import org.junit.Test

/** One diagnostic connection after releasing the unavailable Application provider. No route writes. */
class Stage7NavigationTransportProbeTest {
    @Test fun reportReadOnlySdkConnectionAndCallbackKinds() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MemoryVaultApp
        app.container.navigationEvidence.close()
        val connection = CountDownLatch(1)
        val replies = CountDownLatch(2)
        val events = CopyOnWriteArrayList<String>()
        val helper = NaviHelper(app)
        val listener = object : NaviHelperEventListener {
            override fun onNavigationStatus(status: NavigationStatus) { events.add("STATUS:${status.name}") }
            override fun onError(errorCode: NaviErrorCode) { events.add("ERROR:${errorCode.name}") }
            override fun onDestinationInfo(destinationInfo: DestinationInfo) { events.add("DESTINATION_RESPONSE"); replies.countDown() }
            override fun onRecentDestinationInfo(recentDestinationInfoList: List<RecentDestinationInfo>) { events.add("RECENT_RESPONSE_COUNT:${recentDestinationInfoList.size}"); replies.countDown() }
        }
        try {
            helper.initialize(onConnected = {
                events.add("CONNECTED")
                helper.addListener(listener)
                try { helper.getDestinationInfo(); events.add("DESTINATION_REQUESTED"); helper.getRecentDestinationInfo(); events.add("RECENT_REQUESTED") }
                catch (error: Exception) { events.add("REQUEST_EXCEPTION:${error.javaClass.simpleName}") }
                finally { connection.countDown() }
            }, onFailed = { error -> events.add("CONNECT_FAILURE:${error.javaClass.simpleName}"); connection.countDown() })
            val terminal = connection.await(20, TimeUnit.SECONDS)
            if (events.contains("CONNECTED")) replies.await(6, TimeUnit.SECONDS)
            instrumentation.sendStatus(2, Bundle().apply {
                putString("stage7_navi_transport_events", events.joinToString("|"))
                putBoolean("stage7_navi_connection_terminal", terminal)
            })
            assertTrue("No connection callback within diagnostic bound", terminal)
        } finally { helper.removeListener(listener); helper.release() }
    }
}
