package com.z1pendant

import com.z1pendant.util.Serial
import org.junit.Assert.assertEquals
import org.junit.Test

class SerialTest {
    @Test fun hidesTheNumberOnly() {
        assertEquals("Makera_Z1P", Serial.hide("Makera_Z1P_123456"))
        assertEquals("Makera_Z1", Serial.hide("Makera_Z1_000042"))
        assertEquals("found Makera_Z1P at 192.168.1.48", Serial.hide("found Makera_Z1P_123456 at 192.168.1.48"))
        assertEquals("192.168.1.48", Serial.hide("192.168.1.48"))
        assertEquals("<Idle|MPos:-1.000,-1.000,-1.000>", Serial.hide("<Idle|MPos:-1.000,-1.000,-1.000>"))
        assertEquals("Makera_Z1P_123456", Serial.shown("Makera_Z1P_123456", hide = false))
    }
}
