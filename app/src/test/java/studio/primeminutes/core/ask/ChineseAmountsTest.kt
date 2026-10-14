package studio.primeminutes.core.ask

import org.junit.Assert.assertEquals
import org.junit.Test

class ChineseAmountsTest {
    private fun v(s: String) = ChineseAmounts.value(s)

    @Test fun values() {
        assertEquals(80_000L, v("八萬"))
        assertEquals(82_000L, v("八萬二"))
        assertEquals(110_000L, v("十一萬"))
        assertEquals(12_000L, v("一萬兩千"))
        assertEquals(18_000L, v("一萬八"))
        assertEquals(4_500L, v("四千五"))
        assertEquals(10_005L, v("一萬零五"))
        assertEquals(200_000L, v("二十萬"))
        assertEquals(25_000L, v("兩萬五千"))
        assertEquals(1_500_000L, v("一百五十萬"))
        assertEquals(30_000_000L, v("三千萬"))
        assertEquals(120_000_000L, v("一億二千萬"))
        assertEquals(15_000L, v("1.5萬"))
        assertEquals(20L, v("二十"))
    }

    @Test fun annotate() {
        assertEquals("報價八萬二 (82,000)，不含包裝", ChineseAmounts.annotate("報價八萬二，不含包裝"))
        assertEquals("月租一個四千五 (4,500)，二十個車位", ChineseAmounts.annotate("月租一個四千五，二十個車位"))
        assertEquals("預算二十萬 (200,000)", ChineseAmounts.annotate("預算二十萬"))
        // Words, not amounts; small numbers are left alone.
        assertEquals("千萬不要，萬一下雨", ChineseAmounts.annotate("千萬不要，萬一下雨"))
        assertEquals("三月七號，十個名額", ChineseAmounts.annotate("三月七號，十個名額"))
    }

    @Test fun toDigits() {
        assertEquals("報價82,000，預算200,000，月租4,500，三月七號", ChineseAmounts.toDigits("報價八萬二，預算二十萬，月租四千五，三月七號"))
    }
}
