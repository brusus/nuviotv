package com.nuvio.tv.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DataStoreFileNameTest {

    @Test
    fun `preferences file and its shadow and temp copies resolve to the same store`() {
        assertEquals("layout_settings_p2", dataStoreNameOfFile("layout_settings_p2.preferences_pb"))
        assertEquals("layout_settings_p2", dataStoreNameOfFile("layout_settings_p2.preferences_pb.bak"))
        assertEquals("layout_settings_p2", dataStoreNameOfFile("layout_settings_p2.preferences_pb.tmp"))
    }

    @Test
    fun `unrelated files are not treated as stores`() {
        assertNull(dataStoreNameOfFile("layout_settings_p2.json"))
        assertNull(dataStoreNameOfFile("layout_settings_p2.bak"))
    }

    @Test
    fun `profile suffix match does not catch a longer profile id`() {
        val name = dataStoreNameOfFile("layout_settings_p12.preferences_pb.bak")

        assertEquals("layout_settings_p12", name)
        assertEquals(false, name!!.endsWith("_p1"))
    }
}
