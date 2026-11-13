package app.memoryvault.ui

import app.memoryvault.TypedMemoResult
import app.memoryvault.domain.DriveState
import kotlinx.coroutines.CancellationException

internal interface TypedMemoStore {
    suspend fun add(visitId: String, text: String): Boolean
    suspend fun update(noteId: Long, text: String): Boolean
}

internal class TypedMemoWriter(
    private val currentDrive: () -> DriveState,
    private val store: () -> TypedMemoStore?,
) {
    suspend fun add(visitId: String, text: String): TypedMemoResult = write(text, TypedMemoResult.EMPTY) {
        it.add(visitId, text)
    }

    suspend fun update(noteId: Long, text: String): TypedMemoResult = write(text, TypedMemoResult.NOT_EDITABLE) {
        it.update(noteId, text)
    }

    private suspend fun write(
        text: String,
        rejected: TypedMemoResult,
        mutate: suspend (TypedMemoStore) -> Boolean,
    ): TypedMemoResult {
        if (currentDrive() != DriveState.PARKED) return TypedMemoResult.BLOCKED
        if (text.isBlank()) return TypedMemoResult.EMPTY
        return try {
            val ready = store() ?: return TypedMemoResult.STORAGE_UNAVAILABLE
            if (currentDrive() != DriveState.PARKED) return TypedMemoResult.BLOCKED
            if (mutate(ready)) TypedMemoResult.SAVED else rejected
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            TypedMemoResult.FAILED
        }
    }
}
