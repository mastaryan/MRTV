package com.ipvio.stream

import android.content.pm.ActivityInfo
import org.junit.Assert.*
import org.junit.Test

class LoginFlowTest {
    @Test fun epgGateNeverTraps() {
        assertEquals(EpgGateLogic.Step.WAIT, EpgGateLogic.step(true, 1_000, null))
        assertEquals(EpgGateLogic.Step.ENTER, EpgGateLogic.step(true, EpgGateLogic.TIMEOUT_MS, null))   // timeout: guide keeps loading
        assertEquals(EpgGateLogic.Step.ENTER, EpgGateLogic.step(false, 5_000, null))
        assertEquals(EpgGateLogic.Step.SHOW_ERROR, EpgGateLogic.step(false, 5_000, "HTTP 404"))
        assertTrue(EpgGateLogic.TIMEOUT_MS in 60_000L..90_000L)
    }
    @Test fun epgFraction() {
        assertNull(EpgGateLogic.fraction(500, 0))
        assertEquals(0.5f, EpgGateLogic.fraction(500, 1000)!!, 0.001f)
        assertEquals(1f, EpgGateLogic.fraction(2000, 1000)!!, 0.001f)
    }
    @Test fun orientation() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, OrientationLogic.atStart(false, false))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, OrientationLogic.atStart(false, true))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, OrientationLogic.atStart(true, false))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, OrientationLogic.atStart(true, true))
        assertTrue(OrientationLogic.physicallyLandscape(90)); assertTrue(OrientationLogic.physicallyLandscape(270))
        assertFalse(OrientationLogic.physicallyLandscape(0)); assertFalse(OrientationLogic.physicallyLandscape(180))
        assertFalse(OrientationLogic.physicallyLandscape(-1))
    }
}
