package app.memoryvault.share

interface ShareSelectionStore {
    suspend fun load(date: String): ShareSelection?
    suspend fun save(selection: ShareSelection)
}
