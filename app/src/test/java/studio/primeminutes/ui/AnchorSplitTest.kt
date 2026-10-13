package studio.primeminutes.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AnchorSplitTest {
    @Test fun splitsCommaList() = assertEquals("x [1:48] [12:00]。", splitAnchors("x [1:48, 12:00]。"))
    @Test fun splitsCjkSeparators() = assertEquals("[0:05] [1:02:03]", splitAnchors("[0:05、1:02:03]"))
    @Test fun leavesSingleAndProse() = assertEquals("[4:12] [a, b]", splitAnchors("[4:12] [a, b]"))
}
