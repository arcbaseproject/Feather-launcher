package dev.feather.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {
    @Test fun searchIgnoresCaseAndAccents() {
        assertEquals("cafe", normalize("Café"))
        assertTrue("map" in normalize("Google Maps"))
        assertEquals("", normalize(""))
    }

    @Test fun layoutRoundTrip() {
        val m = mapOf(0 to "com.a/.Main#0", 7 to "com.b/com.b.X#10")
        assertEquals(m, parseLayout(serializeLayout(m)))
        assertEquals(emptyMap<Int, String>(), parseLayout(serializeLayout(emptyMap())))
    }

    @Test fun layoutSkipsGarbage() {
        assertEquals(emptyMap<Int, String>(), parseLayout(null))
        assertEquals(mapOf(3 to "k"), parseLayout("x y\n-1 z\n3 k\n4 \nnospace\n"))
    }

    @Test fun gridShrinkPreservesEverySavedSlot() {
        val dock = mapOf(4 to "unavailable-app")
        assertTrue(!layoutFits(dock, 4))
        assertTrue(layoutFits(dock, 5))
        val home = mapOf(19 to "app")
        assertTrue(!layoutFits(home, 4 * 4))
        assertTrue(layoutFits(home, 4 * 5))
        assertTrue(layoutFits(emptyMap(), 3))
    }

    @Test fun lruEvictsLeastRecentlyUsedByBytes() {
        val lru = IconLru<String>(10) { it.length }
        lru.put("a", "1234")
        lru.put("b", "1234")
        lru["a"] // touch: b is now oldest
        lru.put("c", "1234") // 12 bytes > 10, evicts b
        assertNull(lru["b"])
        assertEquals("1234", lru["a"])
        assertEquals(2, lru.size())
        lru.maxBytes = 4
        lru.trim()
        assertEquals(1, lru.size())
        lru.put("a", "123456789012") // bigger than the cap alone
        assertEquals(0, lru.size())
    }
}
