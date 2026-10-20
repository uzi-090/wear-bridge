package io.wearbridge.watch.battery
import org.junit.Assert.assertEquals
import org.junit.Test
import io.wearbridge.watch.battery.LowBatteryPolicy.Decision.*
class LowBatteryPolicyTest {
    @Test fun thresholdIncludesTenAndZero() { for (p in 0..10) assertEquals(ALERT, LowBatteryPolicy.decide(p, false)) }
    @Test fun aboveThresholdRearmsEvenAfterWarning() { for (p in 11..100) assertEquals(REARM, LowBatteryPolicy.decide(p, true)) }
    @Test fun decreasingLowReadingsDoNotRepeatWarning() { for (p in 0..10) assertEquals(IGNORE, LowBatteryPolicy.decide(p, true)) }
    @Test fun unknownDisconnectOrInvalidReadingsDoNotRearm() { for (p in listOf(null, -1, 101, 255)) assertEquals(IGNORE, LowBatteryPolicy.decide(p, true)) }
    @Test fun recoveryAndSecondLowEpisodeAlertAgain() {
        assertEquals(ALERT, LowBatteryPolicy.decide(10, false))
        assertEquals(IGNORE, LowBatteryPolicy.decide(9, true))
        assertEquals(REARM, LowBatteryPolicy.decide(11, true))
        assertEquals(ALERT, LowBatteryPolicy.decide(10, false))
    }
    @Test fun chargingClearsWarningsEvenWhenLevelUnknown() {
        for (p in listOf(null, 0, 9, 10, 100)) assertEquals(REARM, LowBatteryPolicy.decide(p, true, true))
    }
    @Test fun unknownChargingCannotTriggerLowWarning() {
        assertEquals(IGNORE, LowBatteryPolicy.decide(10, false, null))
        assertEquals(REARM, LowBatteryPolicy.decide(11, true, null))
    }
    @Test fun unpluggingLowStartsNewEpisodeButDoesNotRepeat() {
        assertEquals(REARM, LowBatteryPolicy.decide(8, true, true))
        assertEquals(ALERT, LowBatteryPolicy.decide(8, false, false))
        assertEquals(IGNORE, LowBatteryPolicy.decide(7, true, false))
    }
    @Test fun onlyOwnWatchWarningIsExcludedFromMirroring() {
        assertEquals(true, LowBatteryPolicy.isOwnWatchWarning("io.wearbridge.pairingpoc", "Watch battery low"))
        assertEquals(false, LowBatteryPolicy.isOwnWatchWarning("other.app", "Watch battery low"))
        assertEquals(false, LowBatteryPolicy.isOwnWatchWarning("io.wearbridge.pairingpoc", "Find iPhone"))
    }
}
