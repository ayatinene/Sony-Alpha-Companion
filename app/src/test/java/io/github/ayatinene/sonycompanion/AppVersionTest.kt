package io.github.ayatinene.sonycompanion

import io.github.ayatinene.sonycompanion.data.AppVersion
import org.junit.Assert.*
import org.junit.Test

/** The About screen and diagnostics share [AppVersion.label]; keep the revision suffix predictable. */
class AppVersionTest {
    @Test fun appendsTheRevisionWhenKnown() {
        assertEquals("0.1.16-preview (8ed893f)", AppVersion.label("0.1.16-preview", "8ed893f"))
    }
    @Test fun appendsADirtyRevision() {
        assertEquals("0.1.16-preview (8ed893f-dirty)", AppVersion.label("0.1.16-preview", "8ed893f-dirty"))
    }
    @Test fun omitsAnEmptyRevision() {
        assertEquals("0.1.16-preview", AppVersion.label("0.1.16-preview", ""))
    }
    @Test fun omitsTheUnknownPlaceholder() {
        assertEquals("0.1.16-preview", AppVersion.label("0.1.16-preview", "unknown"))
    }
    @Test fun trimsSurroundingWhitespace() {
        assertEquals("0.1.16-preview (8ed893f)", AppVersion.label("0.1.16-preview", "  8ed893f "))
    }
}
