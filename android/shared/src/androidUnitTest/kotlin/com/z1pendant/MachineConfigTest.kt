package com.z1pendant

import com.z1pendant.link.parseGetReply
import com.z1pendant.link.parseNoSpaceReply
import com.z1pendant.link.parseSetReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MachineConfigTest {
    @Test fun parsesAGetReply() {
        assertEquals("coordinate.anchor1_x" to -190.69,
            parseGetReply("sd: coordinate.anchor1_x is set to -190.69"))
    }

    @Test fun aSetReplyIsNotAGetReply() {
        assertNull(parseGetReply("sd: coordinate.anchor1_x has been set to -190.61"))
    }

    @Test fun parsesASetReply() {
        assertEquals("coordinate.anchor1_x" to -190.61,
            parseSetReply("sd: coordinate.anchor1_x has been set to -190.61"))
    }

    @Test fun aGetReplyIsNotASetReply() {
        assertNull(parseSetReply("sd: coordinate.anchor1_x is set to -190.69"))
    }

    @Test fun parsesANoSpaceRejection() {
        assertEquals("coordinate.anchor1_x",
            parseNoSpaceReply("sd: coordinate.anchor1_x not enough space to overwrite existing key/value"))
    }

    @Test fun otherRepliesAreNotANoSpaceRejection() {
        assertNull(parseNoSpaceReply("sd: coordinate.anchor1_x has been set to -190.61"))
        assertNull(parseNoSpaceReply("sd: coordinate.anchor1_x is set to -190.69"))
    }

    @Test fun aNoSpaceRejectionIsNeitherAGetNorASetReply() {
        val line = "sd: coordinate.anchor1_x not enough space to overwrite existing key/value"
        assertNull(parseGetReply(line))
        assertNull(parseSetReply(line))
    }
}
