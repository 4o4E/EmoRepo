package top.e404.emorepo.git

/** 完成旧一轮同步不能清掉该轮开始后发生的修改。 */
internal data class ModificationSyncState(
    val generation: Long = 0L,
    val syncedGeneration: Long = 0L,
    val dueAtMillis: Long = 0L,
) {
    val hasPendingChanges: Boolean get() = generation > syncedGeneration

    fun afterModification(nowMillis: Long): ModificationSyncState = copy(
        generation = generation + 1L,
        dueAtMillis = nowMillis + DEBOUNCE_MILLIS,
    )

    fun remainingDelayMillis(nowMillis: Long): Long =
        if (hasPendingChanges) (dueAtMillis - nowMillis).coerceAtLeast(0L) else 0L

    fun syncedThrough(completedGeneration: Long): ModificationSyncState = copy(
        syncedGeneration = maxOf(syncedGeneration, completedGeneration),
    )

    companion object {
        const val DEBOUNCE_MILLIS = 10_000L
    }
}
