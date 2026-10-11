package com.alabypedro.mediatools.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {
    @Test
    fun comparesVersions() {
        assertTrue(AppUpdater.isNewer("0.2.0", "0.1.0"))
        assertTrue(AppUpdater.isNewer("0.10.0", "0.9.3"))
        assertTrue(AppUpdater.isNewer("1.0", "0.99.99"))
        assertFalse(AppUpdater.isNewer("0.1.0", "0.1.0"))
        assertFalse(AppUpdater.isNewer("0.1.0", "0.2.0"))
        assertFalse(AppUpdater.isNewer("0.1", "0.1.0"))
    }

    @Test
    fun apkNameMatchesReleaseScript() {
        // release.py: DIST / f"UniversalMediaTools-{version}-{abi}.apk"
        assertEquals("UniversalMediaTools-0.2.0-arm64-v8a.apk", AppUpdater.apkName("0.2.0", "arm64-v8a"))
    }
}
