package com.ascolta.app

import com.ascolta.app.work.CrashGuard
import com.ascolta.app.work.CrashGuard.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class CrashGuardTest {
    @Test
    fun noMarkOrNoCrashMeansCarryOn() {
        assertEquals(Action.CONTINUE, CrashGuard.decide(null, "parakeet-v3", 21, crashed = true))
        // Closed by the user or Android (not a crash): never re-download 650 MB for that.
        assertEquals(Action.CONTINUE, CrashGuard.decide("parakeet-v3|21|0", "parakeet-v3", 21, crashed = false))
    }

    @Test
    fun firstCrashRedownloadsSecondGivesUp() {
        assertEquals(Action.REDOWNLOAD, CrashGuard.decide("parakeet-v3|21|0", "parakeet-v3", 21, crashed = true))
        assertEquals(1, CrashGuard.crashes("parakeet-v3|21|1", "parakeet-v3", 21))
        assertEquals(Action.GIVE_UP, CrashGuard.decide("parakeet-v3|21|1", "parakeet-v3", 21, crashed = true))
    }

    @Test
    fun aMarkForAnotherModelOrEpisodeIsIgnored() {
        assertEquals(Action.CONTINUE, CrashGuard.decide("sensevoice|21|0", "parakeet-v3", 21, crashed = true))
        assertEquals(Action.CONTINUE, CrashGuard.decide("parakeet-v3|20|1", "parakeet-v3", 21, crashed = true))
        assertEquals(Action.CONTINUE, CrashGuard.decide("garbage", "parakeet-v3", 21, crashed = true))
    }
}
