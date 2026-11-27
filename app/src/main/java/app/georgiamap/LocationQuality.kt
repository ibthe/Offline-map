package app.georgiamap

/**
 * Filters Android location fixes before they can move the map or advance navigation.
 * Pure Kotlin so timestamp and accuracy edge cases are covered by local unit tests.
 */
object LocationQuality {
    private const val MAX_FUTURE_SKEW_MS = 10_000L
    private const val MAX_CANDIDATE_AGE_MS = 120_000L
    private const val FRESH_FIX_AGE_MS = 15_000L
    private const val CURRENT_FIX_PROTECTION_MS = 30_000L

    fun isFresh(timestampMs: Long, nowMs: Long, maxAgeMs: Long = FRESH_FIX_AGE_MS): Boolean =
        timestampMs > 0L && timestampMs <= nowMs + MAX_FUTURE_SKEW_MS &&
            nowMs - timestampMs <= maxAgeMs

    fun isUsableForNavigation(timestampMs: Long, accuracyM: Float, nowMs: Long, profile: Int): Boolean {
        val maxAccuracy = when (profile) {
            Profile.CAR -> 75f
            Profile.BIKE -> 50f
            else -> 40f
        }
        return isFresh(timestampMs, nowMs) && accuracyM.isFinite() &&
            accuracyM > 0f && accuracyM <= maxAccuracy
    }

    fun shouldAccept(
        candidateTimeMs: Long,
        candidateAccuracyM: Float,
        currentTimeMs: Long,
        currentAccuracyM: Float,
        nowMs: Long
    ): Boolean {
        if (candidateTimeMs <= 0L || candidateTimeMs > nowMs + MAX_FUTURE_SKEW_MS ||
            nowMs - candidateTimeMs > MAX_CANDIDATE_AGE_MS ||
            candidateAccuracyM.isNaN() || candidateAccuracyM < 0f) return false
        if (currentTimeMs <= 0L) return true

        val currentIsFresh = isFresh(currentTimeMs, nowMs, CURRENT_FIX_PROTECTION_MS)
        if (candidateTimeMs < currentTimeMs - 5_000L) return false
        if (currentIsFresh && currentAccuracyM.isFinite() && currentAccuracyM > 0f &&
            candidateAccuracyM > currentAccuracyM * 1.8f &&
            candidateAccuracyM - currentAccuracyM > 20f) return false

        return candidateTimeMs > currentTimeMs || candidateAccuracyM < currentAccuracyM
    }
}
