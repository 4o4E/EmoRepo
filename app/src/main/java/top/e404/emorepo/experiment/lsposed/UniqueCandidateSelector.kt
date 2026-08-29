package top.e404.emorepo.experiment.lsposed

/**
 * 只接受唯一且通过结构校验的候选，避免混淆定位误命中后 Hook QQ 无关类。
 */
internal fun <T> requireUniqueValidCandidate(
    targetName: String,
    candidates: Iterable<T>,
    isValid: (T) -> Boolean,
): T {
    val valid = candidates.distinct().filter(isValid)
    check(valid.size == 1) {
        "$targetName 需要唯一有效候选，实际为 ${valid.size} 个"
    }
    return valid.single()
}
