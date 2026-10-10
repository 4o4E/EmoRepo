package top.e404.emorepo.git

import android.content.Context
import androidx.core.content.edit

internal class ModificationSyncStore(context: Context) {
    private val preferences = context.getSharedPreferences("emorepo_modification_sync", Context.MODE_PRIVATE)

    fun load(): ModificationSyncState = synchronized(lock) { readState() }

    fun recordModification(nowMillis: Long) = synchronized(lock) {
        writeState(readState().afterModification(nowMillis))
    }

    fun markSynced(generation: Long) = synchronized(lock) {
        writeState(readState().syncedThrough(generation))
    }

    private fun readState(): ModificationSyncState = ModificationSyncState(
        generation = preferences.getLong("generation", 0L),
        syncedGeneration = preferences.getLong("synced_generation", 0L),
        dueAtMillis = preferences.getLong("due_at_millis", 0L),
    )

    private fun writeState(state: ModificationSyncState) {
        preferences.edit(commit = true) {
            putLong("generation", state.generation)
            putLong("synced_generation", state.syncedGeneration)
            putLong("due_at_millis", state.dueAtMillis)
        }
    }

    companion object {
        private val lock = Any()
    }
}
