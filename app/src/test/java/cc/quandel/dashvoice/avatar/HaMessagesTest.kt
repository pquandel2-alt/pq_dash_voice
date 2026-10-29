package cc.quandel.dashvoice.avatar

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HaMessagesTest {

    @Test
    fun `parses initial snapshot, changes and removals`() {
        val event = JSONObject(
            """
            {"a": {"assist_satellite.kueche": {"s": "idle", "a": {}}},
             "c": {"assist_satellite.wohnzimmer": {"+": {"s": "listening", "lc": 1.0}},
                   "input_text.avatar_clip": {"+": {"lu": 2.0}}},
             "r": ["assist_satellite.alt"]}
            """.trimIndent(),
        )
        val updates = HaMessages.parseEntityEvent(event).associateBy { it.entityId }
        assertEquals(HaMessages.StateUpdate("assist_satellite.kueche", "idle", true), updates["assist_satellite.kueche"])
        assertEquals(HaMessages.StateUpdate("assist_satellite.wohnzimmer", "listening", false), updates["assist_satellite.wohnzimmer"])
        assertNull(updates["input_text.avatar_clip"])               // nur Zeitstempel geändert
        assertEquals(HaMessages.StateUpdate("assist_satellite.alt", null, false), updates["assist_satellite.alt"])
    }

    @Test
    fun `filters satellites by prefix`() {
        val states = JSONArray(
            """[{"entity_id":"assist_satellite.a"},{"entity_id":"light.x"},{"entity_id":"assist_satellite.b"}]""",
        )
        assertEquals(listOf("assist_satellite.a", "assist_satellite.b"), HaMessages.entityIdsWithPrefix(states, "assist_satellite."))
    }

    @Test
    fun `clip value yields scene before the space`() {
        assertEquals("happy", HaMessages.sceneFromClipValue("happy 2026-10-29T12:00:00"))
        assertEquals("look_left", HaMessages.sceneFromClipValue(" Look_Left 1730000000 "))
        assertEquals("alert", HaMessages.sceneFromClipValue("alert"))
        assertNull(HaMessages.sceneFromClipValue(""))
        assertNull(HaMessages.sceneFromClipValue("unknown"))
        assertNull(HaMessages.sceneFromClipValue(null))
    }

    @Test
    fun `base and websocket urls derive from dashboard url`() {
        assertEquals("https://ha.example.cc", HaMessages.baseUrl("https://ha.example.cc/lovelace/0?kiosk"))
        assertEquals("http://192.168.178.101:8123", HaMessages.baseUrl("http://192.168.178.101:8123/dashboard-tablet"))
        assertEquals("wss://ha.example.cc/api/websocket", HaMessages.webSocketUrl("https://ha.example.cc"))
        assertEquals("ws://192.168.178.101:8123/api/websocket", HaMessages.webSocketUrl("http://192.168.178.101:8123/"))
    }
}
