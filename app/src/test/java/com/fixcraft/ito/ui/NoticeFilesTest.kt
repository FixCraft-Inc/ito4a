package com.fixcraft.ito.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NoticeFilesTest {
    @Test fun itosOwnTermsComeFirstAndTheManifestIsLeftOut() {
        assertEquals(listOf("ITO-COPYRIGHT.txt", "LICENSE", "THIRD_PARTY_LICENSES.md", "Apache-2.0.txt", "zlib-LICENSE.txt"),
            noticeFiles(listOf("zlib-LICENSE.txt", "manifest.json", "LICENSE", "Apache-2.0.txt", "THIRD_PARTY_LICENSES.md", "ITO-COPYRIGHT.txt")))
    }

    @Test fun aTitleDropsOnlyTheFileExtension() {
        assertEquals("Apache-2.0", noticeTitle("Apache-2.0.txt"))
        assertEquals("THIRD_PARTY_LICENSES", noticeTitle("THIRD_PARTY_LICENSES.md"))
        assertEquals("LICENSE", noticeTitle("LICENSE"))
    }
}
