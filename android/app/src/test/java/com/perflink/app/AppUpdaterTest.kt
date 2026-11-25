package com.perflink.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {
    @Test fun newerVersions() {
        assertTrue(AppUpdater.isNewer("1.20", "1.19"))
        assertTrue(AppUpdater.isNewer("1.10", "1.9"))      // numbers, not text
        assertTrue(AppUpdater.isNewer("2.0", "1.99"))
        assertTrue(AppUpdater.isNewer("1.19.1", "1.19"))
        assertFalse(AppUpdater.isNewer("1.19", "1.19"))
        assertFalse(AppUpdater.isNewer("1.18", "1.19"))
        assertFalse(AppUpdater.isNewer(null, "1.19"))
        assertFalse(AppUpdater.isNewer("", "1.19"))
    }

    @Test fun readsGitHubRelease() {
        val json = """{"tag_name":"v1.20","assets":[
            {"name":"notes.txt","browser_download_url":"https://x/notes.txt"},
            {"name":"PerfLink.apk","browser_download_url":"https://github.com/NirabhraKoley/PerfLink/releases/download/v1.20/PerfLink.apk"}]}"""
        val r = AppUpdater.parseRelease(json)!!
        assertEquals("1.20", r.version)
        assertTrue(r.apkUrl.endsWith("/v1.20/PerfLink.apk"))
    }

    @Test fun releaseWithoutApkIsIgnored() {
        assertNull(AppUpdater.parseRelease("""{"tag_name":"v1.20","assets":[]}"""))
        assertNull(AppUpdater.parseRelease("not json"))
    }
}
