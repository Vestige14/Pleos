package app.memoryvault.share

import app.memoryvault.domain.DriveState
import app.memoryvault.platform.DriveSnapshot
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShareExporterTest {
    // Returning success before publish or omitting the read/chooser boundary loses privacy/atomicity.
    @Test fun successPublishesCompletePngThenOpensChooserForOnlyThatUri() = runTest {
        val h = Harness()
        val result = h.exporter().export(draft()) as ShareExportResult.Saved
        assertEquals("own", result.uri)
        assertTrue(result.chooserDispatched)
        assertArrayEquals(byteArrayOf(1, 2, 3), h.gallery.bytes["own"]!!.toByteArray())
        assertEquals(listOf("own"), h.gallery.published)
        assertEquals(listOf("own"), h.chosen)
        assertEquals(setOf("p1"), h.checked.toSet())
        assertTrue(h.gallery.deleted.isEmpty())
    }

    @Test fun unsafeStartNeverRendersInsertsOrLaunches() = runTest {
        for (state in listOf(DriveState.UNKNOWN, DriveState.TRANSITION, DriveState.DRIVING)) {
            val h = Harness(); h.drive.value = DriveSnapshot(state = state)
            assertEquals(ShareExportResult.Blocked, h.exporter().export(draft()))
            assertEquals(0, h.renders); assertTrue(h.gallery.rows.isEmpty()); assertTrue(h.chosen.isEmpty())
        }
    }

    @Test fun unreadablePhotoAndRenderFailureCannotCreateGalleryRow() = runTest {
        val h = Harness(); h.readable = false
        assertEquals(ShareExportResult.Failed(ShareExportFailure.PHOTO_UNAVAILABLE), h.exporter().export(draft()))
        assertTrue(h.gallery.rows.isEmpty())
        h.readable = true
        h.render = { _, _ -> ShareRenderResult.Failure(ShareRenderFailure.TOO_TALL) }
        assertEquals(ShareExportResult.Failed(ShareExportFailure.TOO_LARGE), h.exporter().export(draft()))
        assertTrue(h.gallery.rows.isEmpty()); assertTrue(h.chosen.isEmpty())
    }

    @Test fun storageWriteFailureRemovesOnlyThisPendingRow() = runTest {
        val h = Harness()
        h.gallery.rows["existing"] = false
        h.gallery.onWrite = { throw IOException("private path") }
        assertEquals(ShareExportResult.Failed(ShareExportFailure.STORAGE_FAILED), h.exporter().export(draft()))
        assertEquals(mapOf("existing" to false), h.gallery.rows)
        assertEquals(listOf("own"), h.gallery.deleted)
        assertTrue(h.chosen.isEmpty())
    }

    @Test fun departureDuringWriteCleansPendingWithoutPublishingOrSharing() = runTest {
        val h = Harness()
        h.gallery.onWrite = { h.drive.value = DriveSnapshot(state = DriveState.DRIVING) }
        assertEquals(ShareExportResult.Blocked, h.exporter().export(draft()))
        assertTrue(h.gallery.rows.isEmpty()); assertTrue(h.gallery.published.isEmpty()); assertTrue(h.chosen.isEmpty())
    }

    @Test fun collapsedUnknownRecoveryDuringRenderInvalidatesTheOriginalEpoch() = runTest {
        val h = Harness()
        h.render = { _, _ ->
            h.drive.value = DriveSnapshot(state = DriveState.UNKNOWN, invalidationGeneration = 1)
            h.drive.value = DriveSnapshot(state = DriveState.PARKED, invalidationGeneration = 1)
            ShareRenderResult.Png(byteArrayOf(1), 1080, 1)
        }
        assertEquals(ShareExportResult.Blocked, h.exporter().export(draft()))
        assertTrue(h.gallery.rows.isEmpty()); assertTrue(h.chosen.isEmpty())
    }

    @Test fun revokedPermissionAfterWriteLeavesNoPublishedOrPendingFile() = runTest {
        val h = Harness(); h.gallery.onWrite = { h.readable = false }
        assertEquals(ShareExportResult.Failed(ShareExportFailure.PHOTO_UNAVAILABLE), h.exporter().export(draft()))
        assertTrue(h.gallery.rows.isEmpty()); assertTrue(h.chosen.isEmpty())
    }

    @Test fun callerCancellationDuringWriteCleansPendingAndPropagates() = runTest {
        val h = Harness()
        val exporter = h.exporter()
        val work = launch {
            h.gallery.onWrite = { coroutineContext[ kotlinx.coroutines.Job ]!!.cancel() }
            exporter.export(draft())
            fail("Cancelled export cannot return a success URI")
        }
        work.join()
        assertTrue(work.isCancelled)
        assertTrue(h.gallery.rows.isEmpty()); assertTrue(h.gallery.published.isEmpty()); assertTrue(h.chosen.isEmpty())
    }

    @Test fun safetyObserverCancelsSuspendedRenderWithoutWaitingForItsResult() = runTest {
        val h = Harness()
        val entered = CompletableDeferred<Unit>()
        var cancelled = false
        h.render = { _, _ ->
            entered.complete(Unit)
            try { awaitCancellation() } finally { cancelled = true }
        }
        val work = async { h.exporter().export(draft()) }
        runCurrent(); assertTrue("Render must have begun", entered.isCompleted)
        h.drive.value = DriveSnapshot(state = DriveState.UNKNOWN, invalidationGeneration = 1)
        runCurrent()
        assertTrue(cancelled)
        assertEquals(ShareExportResult.Blocked, work.await())
        assertTrue(h.gallery.rows.isEmpty())
    }

    @Test fun noChooserKeepsPublishedFileAndReturnsSavedWithNoChooser() = runTest {
        val h = Harness(); h.chooser = { false }
        val result = h.exporter().export(draft()) as ShareExportResult.Saved
        assertFalse(result.chooserDispatched)
        assertEquals(mapOf("own" to false), h.gallery.rows)
        assertTrue(h.gallery.deleted.isEmpty())
    }

    @Test fun chooserExceptionCannotDeleteAnAlreadyPublishedFile() = runTest {
        val h = Harness(); h.chooser = { error("private activity failure") }
        val result = h.exporter().export(draft()) as ShareExportResult.Saved
        assertFalse(result.chooserDispatched)
        assertEquals(mapOf("own" to false), h.gallery.rows)
        assertTrue(h.gallery.deleted.isEmpty())
    }

    @Test fun driveInvalidationAtPublishBoundaryPreventsChooserAndKeepsPublicFile() = runTest {
        val h = Harness(); h.gallery.onPublish = { h.drive.value = DriveSnapshot(state = DriveState.DRIVING) }
        assertEquals(ShareExportResult.Blocked, h.exporter().export(draft()))
        assertEquals(mapOf("own" to false), h.gallery.rows)
        assertTrue(h.gallery.deleted.isEmpty()); assertTrue(h.chosen.isEmpty())
    }

    @Test fun publishOutcomeAmbiguityNeverDeletesAFileThatBecamePublic() = runTest {
        val h = Harness(); h.gallery.onPublish = { throw IOException("provider failed after commit") }
        assertEquals(ShareExportResult.Failed(ShareExportFailure.STORAGE_FAILED), h.exporter().export(draft()))
        assertEquals(mapOf("own" to false), h.gallery.rows)
        assertTrue(h.chosen.isEmpty())
    }

    @Test fun repeatedExportWhileOneIsRunningDoesNotStartAnotherRender() = runTest {
        val h = Harness()
        h.render = { _, _ -> awaitCancellation() }
        val exporter = h.exporter()
        val first = launch { exporter.export(draft()) }
        runCurrent()
        assertEquals(1, h.renders)
        assertEquals(ShareExportResult.Busy, exporter.export(draft()))
        assertEquals(1, h.renders)
        first.cancelAndJoin()
    }

    // A cancellation hook must unblock native/provider work before that work can return normally.
    @Test fun callerCancellationSignalsBlockedIoBeforeWaitingForItToFinish() = runBlocking {
        val h = Harness()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val signalled = CompletableDeferred<Boolean>()
        h.render = { _, guard ->
            guard.onCancel { release.countDown() }
            entered.countDown()
            signalled.complete(release.await(2, TimeUnit.SECONDS))
            guard.check()
            ShareRenderResult.Png(byteArrayOf(1), 1080, 1)
        }
        val work = launch(Dispatchers.IO) { h.exporter().export(draft()) }
        withContext(Dispatchers.IO) { assertTrue("Native read must have begun", entered.await(3, TimeUnit.SECONDS)) }
        work.cancel()
        assertTrue("Cancellation must signal IO before its timeout", withTimeout(4_000) { signalled.await() })
        work.join()
        assertTrue(h.gallery.rows.isEmpty())
    }

    // The external chooser can synchronously stop/cancel the Activity; durable receipt must already exist.
    @Test fun confirmedPublishPersistsReceiptBeforeChooserEvenWhenChooserThrows() = runTest {
        val h = Harness()
        h.chooser = {
            assertEquals(ShareExportReceipt("own", "synthetic.png", "2026-11-08"), h.receipts.latest.value)
            throw IOException("chooser outcome unknown")
        }
        h.exporter().export(draft())
        assertEquals(ShareExportReceipt("own", "synthetic.png", "2026-11-08"), h.receipts.latest.value)
        assertEquals(mapOf("own" to false), h.gallery.rows)
    }

    @Test fun unpublishedFailureCannotCreateReceiptOrReplaceAnEarlierSuccessfulOne() = runTest {
        val h = Harness()
        val earlier = ShareExportReceipt("previous", "earlier.png", "2026-11-07")
        h.receipts.save(earlier)
        h.gallery.onWrite = { throw IOException("write failed") }
        assertEquals(ShareExportResult.Failed(ShareExportFailure.STORAGE_FAILED), h.exporter().export(draft()))
        assertEquals(earlier, h.receipts.latest.value)
        assertTrue(h.gallery.rows.isEmpty())
    }

    @Test fun confirmedPublicationReceiptSurvivesImmediateDepartureBeforeChooser() = runTest {
        val h = Harness()
        h.gallery.onPublish = { h.drive.value = DriveSnapshot(state = DriveState.DRIVING) }
        assertEquals(ShareExportResult.Blocked, h.exporter().export(draft()))
        assertEquals(ShareExportReceipt("own", "synthetic.png", "2026-11-08"), h.receipts.latest.value)
        assertEquals(mapOf("own" to false), h.gallery.rows)
        assertTrue(h.chosen.isEmpty())
    }

    @Test fun lifecycleCancellationDuringChooserKeepsReceiptAndPublicFile() = runTest {
        val h = Harness()
        val entered = CompletableDeferred<Unit>()
        h.chooser = { entered.complete(Unit); awaitCancellation() }
        val work = launch { h.exporter().export(draft()) }
        runCurrent(); assertTrue("Chooser must have been reached", entered.isCompleted)
        work.cancelAndJoin()
        assertEquals(ShareExportReceipt("own", "synthetic.png", "2026-11-08"), h.receipts.latest.value)
        assertEquals(mapOf("own" to false), h.gallery.rows)
    }

    @Test fun receiptCommitFinishesDespiteCancellationImmediatelyAfterPublication() = runTest {
        val h = Harness()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        h.receipts.beforeSave = { entered.complete(Unit); release.await() }
        val work = launch { h.exporter().export(draft()) }
        runCurrent(); assertTrue("Published file must begin its durable receipt", entered.isCompleted)
        work.cancel(); release.complete(Unit); work.join()
        assertEquals(ShareExportReceipt("own", "synthetic.png", "2026-11-08"), h.receipts.latest.value)
        assertEquals(mapOf("own" to false), h.gallery.rows)
    }

    @Test fun receiptStorageFailureKeepsConfirmedImageAndDoesNotLaunchChooser() = runTest {
        val h = Harness()
        h.receipts.beforeSave = { throw IOException("receipt store unavailable") }
        val result = h.exporter().export(draft())
        assertEquals(ShareExportResult.Saved("own", chooserDispatched = false, receiptPersisted = false), result)
        assertEquals(mapOf("own" to false), h.gallery.rows)
        assertTrue("Chooser may not run before a durable receipt", h.chosen.isEmpty())
        assertNull(h.receipts.latest.value)
    }

    private class Harness {
        val drive = MutableStateFlow(DriveSnapshot(state = DriveState.PARKED))
        val gate = ShareSessionGate(drive).apply { resume() }
        val gallery = Gallery()
        val receipts = Receipts()
        var readable = true
        var renders = 0
        val checked = mutableListOf<String>()
        val chosen = mutableListOf<String>()
        var render: suspend (ShareDraft, ShareExportGuard) -> ShareRenderResult = { _, _ ->
            ShareRenderResult.Png(byteArrayOf(1, 2, 3), 1080, 1)
        }
        var chooser: suspend (String) -> Boolean = { chosen += it; true }
        fun exporter() = ShareExporter(gate, gallery, SharePngRenderer { draft, guard ->
            renders++; render(draft, guard)
        }, SharePhotoAccess { uri, _ -> checked += uri; readable }, ShareChooser { chooser(it) }, { "synthetic.png" }, receipts)
    }

    private class Receipts : ShareExportReceiptStore {
        override val latest = MutableStateFlow<ShareExportReceipt?>(null)
        var beforeSave: suspend () -> Unit = {}
        override suspend fun save(receipt: ShareExportReceipt) { beforeSave(); latest.value = receipt }
    }

    private class Gallery : ShareGalleryIo {
        val rows = mutableMapOf<String, Boolean>() // true=pending; unrelated existing rows must survive.
        val bytes = mutableMapOf<String, ByteArrayOutputStream>()
        val published = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        var onWrite: () -> Unit = {}
        var onPublish: () -> Unit = {}
        override fun createPending(displayName: String): String { rows["own"] = true; return "own" }
        override fun openOutput(uri: String): OutputStream = object : ByteArrayOutputStream() {
            override fun write(b: ByteArray, off: Int, len: Int) { onWrite(); super.write(b, off, len) }
        }.also { bytes[uri] = it }
        override fun publish(uri: String): Boolean { rows[uri] = false; published += uri; onPublish(); return true }
        override fun deletePending(uri: String) { if (rows[uri] == true) { rows.remove(uri); deleted += uri } }
    }

    private fun draft() = ShareDraft("2026-11-08", listOf(ShareItem(1, "식당", listOf("p1"), null)))
}
