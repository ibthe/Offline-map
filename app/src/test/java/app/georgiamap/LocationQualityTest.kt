package app.georgiamap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationQualityTest {
    private val now = 1_000_000L

    @Test
    fun rejectsStaleAndFutureFixesForNavigation() {
        assertFalse(LocationQuality.isUsableForNavigation(now - 16_000L, 5f, now, Profile.CAR))
        assertFalse(LocationQuality.isUsableForNavigation(now + 20_000L, 5f, now, Profile.CAR))
        assertTrue(LocationQuality.isUsableForNavigation(now - 5_000L, 20f, now, Profile.CAR))
    }

    @Test
    fun accuracyThresholdDependsOnTravelProfile() {
        assertTrue(LocationQuality.isUsableForNavigation(now, 70f, now, Profile.CAR))
        assertFalse(LocationQuality.isUsableForNavigation(now, 70f, now, Profile.FOOT))
        assertTrue(LocationQuality.isUsableForNavigation(now, 45f, now, Profile.BIKE))
        assertFalse(LocationQuality.isUsableForNavigation(now, Float.POSITIVE_INFINITY, now, Profile.BIKE))
    }

    @Test
    fun recentAccurateGpsIsNotReplacedByCoarseNetworkFix() {
        assertFalse(LocationQuality.shouldAccept(now, 90f, now - 1_000L, 8f, now))
        assertTrue(LocationQuality.shouldAccept(now, 12f, now - 1_000L, 30f, now))
    }

    @Test
    fun newerFixCanReplaceOldFixAndStaleCurrentFixDoesNotBlockRecovery() {
        assertTrue(LocationQuality.shouldAccept(now, 100f, now - 40_000L, 5f, now))
        assertTrue(LocationQuality.shouldAccept(now - 2_000L, 12f, now - 40_000L, 5f, now))
        assertFalse(LocationQuality.shouldAccept(now - 6_000L, 5f, now, 10f, now))
    }

    @Test
    fun rejectsInvalidOrVeryOldTimestamps() {
        assertFalse(LocationQuality.shouldAccept(0L, 5f, 0L, 0f, now))
        assertFalse(LocationQuality.shouldAccept(now - 121_000L, 5f, 0L, 0f, now))
        assertFalse(LocationQuality.shouldAccept(now, Float.NaN, 0L, 0f, now))
    }
}
