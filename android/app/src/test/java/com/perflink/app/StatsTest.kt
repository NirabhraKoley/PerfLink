package com.perflink.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StatsTest {
    private fun ram(vararg m: RamModule) = Ram(8000.0, 16000.0, 50.0, m.toList())
    private val a = RamModule(8, 3200, "DDR4", "Controller0-ChannelA-DIMM0")
    private val b = RamModule(8, 3200, "DDR4", "Controller1-ChannelA-DIMM0")

    @Test fun splitShowsEachModule() {
        assertNull(ram().split())
        assertEquals("16 GB", ram(RamModule(16, 3200, "DDR4", "DIMM0")).split())
        assertEquals("8 GB + 8 GB", ram(a, b).split())
    }

    @Test fun layoutDualChannelSameKind() {
        assertEquals("8 GB + 8 GB · DDR4-3200 · dual channel", ram(a, b).layout())
    }

    @Test fun layoutSingleModule() {
        assertEquals("16 GB (one module) · DDR5-4800", ram(RamModule(16, 4800, "DDR5", "DIMM1")).layout())
    }

    @Test fun layoutMixedModulesDropsKind() {
        // speeds differ: each module's own row says it, the summary doesn't
        assertEquals("8 GB + 16 GB · dual channel", ram(a, RamModule(16, 2666, "DDR4", "Controller1-ChannelA-DIMM0")).layout())
    }

    @Test fun layoutUnknownSlotsNoChannelClaim() {
        assertEquals("8 GB + 8 GB · DDR4-3200", ram(a.copy(slot = "DIMM 1"), b.copy(slot = "DIMM 2")).layout())
        assertNull(ram().layout())
    }

    @Test fun parseStatsReadsIdentityAndModules() {
        val json = """{"type":"stats","host":"DESK","user":"Nirabhra Koley","kind":"Laptop",
            "ram":{"used_mb":9000,"total_mb":16000,"util":56,
              "modules":[{"gb":8,"mts":3200,"type":"DDR4","slot":"ChannelA-DIMM0"},{"gb":8,"mts":null,"type":null,"slot":"ChannelB-DIMM0"}]}}"""
        val s = parseStats(json)
        assertNotNull(s); s!!
        assertEquals("DESK", s.host)
        assertEquals("Nirabhra Koley", s.user)
        assertEquals("Laptop", s.kind)
        assertEquals(2, s.ram.modules.size)
        assertEquals(RamModule(8, 3200, "DDR4", "ChannelA-DIMM0"), s.ram.modules[0])
        assertNull(s.ram.modules[1].mts)
        assertNull(s.ram.modules[1].type)
        assertEquals(9000.0, s.ram.usedMb!!, 0.0)
    }

    @Test fun parseStatsOlderAgentWithoutNewFields() {
        val s = parseStats("""{"type":"stats","host":"OLD","ram":{"used_mb":1,"total_mb":2}}""")!!
        assertNull(s.user); assertNull(s.kind)
        assertEquals(emptyList<RamModule>(), s.ram.modules)
    }

    @Test fun parseStatsRejectsOtherMessages() {
        assertNull(parseStats("""{"type":"art"}"""))
        assertNull(parseStats("not json"))
    }

    @Test fun pcKind() {
        assertEquals("💻 Laptop", pcKindLabel("laptop"))
        assertEquals("🖥️ PC", pcKindLabel("PC"))
        assertEquals("🖥️ PC", pcKindLabel(""))
    }
}
