package app.memoryvault.share

import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ShareFlowState(
    val input: ShareInput? = null,
    val selection: ShareSelection? = null,
    val saving: Boolean = false,
    val persistenceFailed: Boolean = false,
)

/** Activity-owned state; accepted writes are serialized and finish even when the Activity closes. */
class ShareFlowController(private val store: ShareSelectionStore) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(ShareFlowState())
    val state = mutableState.asStateFlow()

    suspend fun open(input: ShareInput) = mutex.withLock {
        currentCoroutineContext().ensureActive()
        val copy = input.eligibleCopy()
        if (mutableState.value.selection?.date == input.date) {
            mutableState.value = mutableState.value.copy(input = copy)
            return@withLock
        }
        mutableState.value = ShareFlowState(input = copy, saving = true)
        try {
            val loaded = store.load(input.date)?.takeIf { it.date == input.date }?.ownedCopy()
            val selected = loaded ?: ShareSelection(input.date, copy.visits.map { it.id }.toSet(),
                copy.visits.flatMap { it.photos }.map { it.uri }.toSet(), emptyMap()).ownedCopy()
            mutableState.value = ShareFlowState(copy, selected, saving = loaded == null)
            if (loaded == null) persist(selected)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = ShareFlowState(copy, persistenceFailed = true)
        }
    }

    suspend fun setVisit(id: String, included: Boolean) = edit { current, input ->
        if (input.visits.none { it.id == id }) current
        else current.copy(includedVisitIds = if (included) current.includedVisitIds + id else current.includedVisitIds - id)
    }

    suspend fun setPhoto(uri: String, selected: Boolean) = edit { current, input ->
        if (input.visits.none { visit -> visit.photos.any { it.uri == uri } }) current
        else current.copy(selectedPhotoUris = if (selected) current.selectedPhotoUris + uri else current.selectedPhotoUris - uri)
    }

    suspend fun setCaption(id: String, text: String) = edit { current, input ->
        if (input.visits.none { it.id == id }) current
        else current.copy(captions = current.captions + (id to text))
    }

    suspend fun retrySave() {
        val reload = mutex.withLock {
            val current = mutableState.value
            if (current.selection != null) { persist(current.selection); null }
            else current.input?.also { mutableState.value = ShareFlowState() }
        }
        // A load failure is retried as a load, never as an overwrite of an unread existing row.
        if (reload != null) open(reload)
    }

    fun preview(): ShareDraft? {
        val current = mutableState.value
        if (current.saving || current.persistenceFailed) return null
        return ShareDraftBuilder.build(current.selection ?: return null, current.input ?: return null)
    }

    private suspend fun edit(change: (ShareSelection, ShareInput) -> ShareSelection) = mutex.withLock {
        currentCoroutineContext().ensureActive()
        val current = mutableState.value
        val selected = current.selection ?: return@withLock
        val input = current.input ?: return@withLock
        val next = change(selected, input).ownedCopy()
        if (next != selected || current.persistenceFailed) persist(next)
    }

    private suspend fun persist(selection: ShareSelection) {
        mutableState.value = mutableState.value.copy(selection = selection, saving = true, persistenceFailed = false)
        // A later edit waits on the mutex. An accepted visible change cannot be cancelled halfway through its DB write.
        withContext(NonCancellable) {
            try {
                store.save(selection)
                mutableState.value = mutableState.value.copy(saving = false)
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(saving = false, persistenceFailed = true)
            }
        }
    }
}

internal fun ShareSelection.ownedCopy() = copy(
    includedVisitIds = Collections.unmodifiableSet(LinkedHashSet(includedVisitIds)),
    selectedPhotoUris = Collections.unmodifiableSet(LinkedHashSet(selectedPhotoUris)),
    captions = Collections.unmodifiableMap(LinkedHashMap(captions)),
)

internal fun ShareInput.eligibleCopy() = ShareInput(date, Collections.unmodifiableList(visits
    .filter { it.date == date }
    .sortedWith(compareBy<ShareVisitInput> { it.arrivedAt }.thenBy { it.id })
    .map { visit -> visit.copy(photos = Collections.unmodifiableList(visit.photos.filter {
        it.accessible && it.linkState in setOf(SharePhotoState.CANDIDATE, SharePhotoState.CONFIRMED)
    })) }))
