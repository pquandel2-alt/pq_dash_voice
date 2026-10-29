package cc.quandel.dashvoice.avatar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ClipLibraryTest {

    private fun lib(vararg names: String) =
        ClipLibrary(names.map { AvatarClip(it, "/cache/$it") }, Random(42))

    @Test
    fun `base name strips variant number`() {
        assertEquals("idle", AvatarClip.baseOf("idle_1.mp4"))
        assertEquals("speaking_a", AvatarClip.baseOf("speaking_a_12.mp4"))
        assertEquals("look_left", AvatarClip.baseOf("look_left_1.mp4"))
        assertEquals("party", AvatarClip.baseOf("party.mp4"))
    }

    @Test
    fun `speaking groups letter variants, look does not swallow look_left`() {
        val l = lib("speaking_a_1.mp4", "speaking_b_1.mp4", "speaking_c_1.mp4", "look_left_1.mp4", "idle_1.mp4")
        assertEquals(3, l.variants("speaking").size)
        assertEquals(1, l.variants("look_left").size)
        assertFalse(l.has("look"))
        assertEquals(1, l.variants("speaking_a").size)
    }

    @Test
    fun `random pick never repeats the same variant twice in a row`() {
        val l = lib("idle_1.mp4", "idle_2.mp4", "idle_3.mp4")
        var last = l.pick("idle")!!.fileName
        repeat(200) {
            val next = l.pick("idle")!!.fileName
            assertNotEquals(last, next)
            last = next
        }
    }

    @Test
    fun `pick uses all variants`() {
        val l = lib("idle_1.mp4", "idle_2.mp4", "idle_3.mp4")
        val seen = (1..100).map { l.pick("idle")!!.fileName }.toSet()
        assertEquals(3, seen.size)
    }

    @Test
    fun `single variant may repeat and unknown scene yields null`() {
        val l = lib("listening_1.mp4")
        assertEquals("listening_1.mp4", l.pick("listening")!!.fileName)
        assertEquals("listening_1.mp4", l.pick("LISTENING")!!.fileName)
        assertNull(l.pick("thinking"))
        assertTrue(l.has("listening"))
    }
}
