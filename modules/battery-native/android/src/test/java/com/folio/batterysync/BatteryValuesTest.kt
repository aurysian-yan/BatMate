package com.folio.batterysync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryValuesTest {
    @Test fun rejectsUnknownAndOutOfRange() {
        assertNull(BatteryValues.percentage(-1, 100))
        assertNull(BatteryValues.percentage(50, 0))
        assertNull(BatteryValues.percentage(101, 100))
    }

    @Test fun preservesZeroAndNormalizesScaleWithoutOverflow() {
        assertEquals(0, BatteryValues.percentage(0, 100))
        assertEquals(50, BatteryValues.percentage(128, 256))
        assertEquals(100, BatteryValues.percentage(Int.MAX_VALUE, Int.MAX_VALUE))
    }
}
