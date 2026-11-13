package app.memoryvault.ui

import app.memoryvault.TypedMemoResult
import app.memoryvault.domain.DriveState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TypedMemoWriterTest {
    // Dropping either live gate would let a stale dialog mutate storage after leaving P.
    @Test fun addAndEdit_recheckCurrentParkImmediatelyBeforeMutation() = runTest {
        var drive = DriveState.PARKED
        val store = InMemoryTypedStore()
        val writer = TypedMemoWriter({ drive }) { drive = DriveState.DRIVING; store }
        assertEquals(TypedMemoResult.BLOCKED, writer.add("v1", "남겨 둔 문장"))
        drive = DriveState.PARKED
        assertEquals(TypedMemoResult.BLOCKED, writer.update(4, "변경 문장"))
        assertTrue(store.values.isEmpty())
    }

    @Test fun allNonParkedStates_rejectAddAndEditWithoutOpeningStorage() = runTest {
        for (drive in listOf(DriveState.UNKNOWN, DriveState.TRANSITION, DriveState.DRIVING)) {
            val writer = TypedMemoWriter({ drive }) { error("Storage must remain untouched") }
            assertEquals(TypedMemoResult.BLOCKED, writer.add("v1", "직접 메모"))
            assertEquals(TypedMemoResult.BLOCKED, writer.update(1, "직접 메모"))
        }
    }

    @Test fun parkedAddAndEdit_keepTheExactTextAndWriteOnce() = runTest {
        val store = InMemoryTypedStore()
        val writer = TypedMemoWriter({ DriveState.PARKED }) { store }
        assertEquals(TypedMemoResult.SAVED, writer.add("v2", "  해변에서\n기억할 일  "))
        assertEquals(listOf("v2:  해변에서\n기억할 일  "), store.values)
        assertEquals(TypedMemoResult.SAVED, writer.update(7, "  고친 문장  "))
        assertEquals(listOf("v2:  해변에서\n기억할 일  ", "7:  고친 문장  "), store.values)
    }

    @Test fun blankAndUnavailableAndMissingEdit_areTruthfulAndDoNotReportSaved() = runTest {
        val store = InMemoryTypedStore()
        val writer = TypedMemoWriter({ DriveState.PARKED }) { store }
        assertEquals(TypedMemoResult.EMPTY, writer.add("v1", " \n "))
        assertEquals(TypedMemoResult.EMPTY, writer.update(1, ""))
        assertTrue(store.values.isEmpty())
        assertEquals(TypedMemoResult.STORAGE_UNAVAILABLE,
            TypedMemoWriter({ DriveState.PARKED }) { null }.add("v1", "메모"))
        assertEquals(TypedMemoResult.NOT_EDITABLE,
            TypedMemoWriter({ DriveState.PARKED }) { InMemoryTypedStore(editable = false) }.update(3, "원문 수정 시도"))
    }

    @Test fun storageFailure_isClassifiedWithoutLeakingItsPayload_andCancellationPropagates() = runTest {
        val store = object : TypedMemoStore {
            override suspend fun add(visitId: String, text: String): Boolean = error("private storage payload")
            override suspend fun update(noteId: Long, text: String): Boolean = throw CancellationException("cancel")
        }
        val writer = TypedMemoWriter({ DriveState.PARKED }) { store }
        assertEquals(TypedMemoResult.FAILED, writer.add("v1", "문장"))
        try {
            writer.update(1, "문장")
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }

    private class InMemoryTypedStore(private val editable: Boolean = true) : TypedMemoStore {
        val values = mutableListOf<String>()
        override suspend fun add(visitId: String, text: String): Boolean {
            values += "$visitId:$text"
            return true
        }
        override suspend fun update(noteId: Long, text: String): Boolean {
            if (editable) values += "$noteId:$text"
            return editable
        }
    }
}
