package studio.primeminutes.core.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UserEditsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun roundTripsAndDropsTheFileWhenNothingIsEdited() {
        val dir = tmp.newFolder()
        assertEquals(UserEdits(), UserEdits.read(dir))
        UserEdits.write(dir, UserEdits(summary = true))
        assertEquals(UserEdits(summary = true), UserEdits.read(dir))
        UserEdits.write(dir, UserEdits(summary = true, lines = true))
        assertEquals(UserEdits(true, true), UserEdits.read(dir))
        UserEdits.write(dir, UserEdits())
        assertFalse(File(dir, UserEdits.FILE).exists())
    }

    @Test fun aCorruptOrMissingFileReadsAsUnedited() {
        val dir = tmp.newFolder()
        File(dir, UserEdits.FILE).writeText("{not json")
        assertEquals(UserEdits(), UserEdits.read(dir))
        assertEquals(UserEdits(), UserEdits.read(null))
    }
}
