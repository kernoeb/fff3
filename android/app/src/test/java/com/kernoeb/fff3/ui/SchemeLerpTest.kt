package com.kernoeb.fff3.ui

import androidx.compose.ui.graphics.Color
import com.kernoeb.fff3.ui.theme.lerp
import com.kernoeb.fff3.ui.theme.stripDarkColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SchemeLerpTest {

    private val red = stripDarkColorScheme(Color(0xFFFF0000))
    private val blue = stripDarkColorScheme(Color(0xFF0000FF))

    @Test
    fun endsMatchTheirSchemes() {
        assertEquals(red.primary, lerp(red, blue, 0f).primary)
        assertEquals(blue.primary, lerp(red, blue, 1f).primary)
        assertEquals(blue.surfaceContainerHighest, lerp(red, blue, 1f).surfaceContainerHighest)
    }

    @Test
    fun middleIsNeitherEnd() {
        val middle = lerp(red, blue, 0.5f)
        assertNotEquals(red.primary, middle.primary)
        assertNotEquals(blue.primary, middle.primary)
    }
}
