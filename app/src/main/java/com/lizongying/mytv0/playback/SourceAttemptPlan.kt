package com.lizongying.mytv0.playback

/** One finite request: start at the selected address, wrap once, and skip duplicate URLs. */
class SourceAttemptPlan(urls: List<String>, startIndex: Int, maxAttempts: Int = 5) {
    data class Attempt(val index: Int, val url: String)

    private val candidates: List<Attempt>
    val availableSourceCount: Int
    var attemptedCount: Int = 0
        private set
    val totalAttempts: Int get() = candidates.size
    val hasNext: Boolean get() = attemptedCount < totalAttempts
    val isLimited: Boolean get() = availableSourceCount > totalAttempts

    init {
        require(maxAttempts > 0) { "An attempt limit must be positive" }
        val first = if (urls.isEmpty()) 0 else startIndex.coerceIn(urls.indices)
        val distinct = (urls.indices).map { offset ->
            val index = (first + offset) % urls.size
            Attempt(index, urls[index])
        }.filter { it.url.isNotBlank() }.distinctBy { it.url }
        availableSourceCount = distinct.size
        candidates = distinct.take(maxAttempts)
    }

    fun next(): Attempt? {
        if (!hasNext) return null
        return candidates[attemptedCount++]
    }
}
