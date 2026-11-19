package com.z1pendant

import com.z1pendant.link.MachineName
import com.z1pendant.link.MachineTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MachineNameTest {
    @Test fun parsesTheConfigReply() {
        assertEquals("Makera_Z1P_123456", MachineName.parse("sd: wifi.machine_name is set to Makera_Z1P_123456\r\n"))
        assertEquals("My Z1", MachineName.parse("sd: wifi.machine_name is set to My Z1"))
        assertNull(MachineName.parse("sd: wifi.machine_name is not in config"))
        assertNull(MachineName.parse("sd: coordinate.anchor1_x is set to -190.69"))
        assertNull(MachineName.parse("ok"))
    }

    @Test fun onlyAddressTargetsAreLookedUp() {
        assertTrue(MachineName.needsLookup(MachineTarget("192.168.1.48", "192.168.1.48", 2222)))
        assertTrue(MachineName.needsLookup(MachineTarget("127.0.0.1", "127.0.0.1", 2222)))
        assertFalse(MachineName.needsLookup(MachineTarget("Makera_Z1P_123456", "192.168.1.48", 2222)))
    }
}
