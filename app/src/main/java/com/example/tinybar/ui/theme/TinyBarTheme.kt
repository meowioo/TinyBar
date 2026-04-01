package com.example.tinybar.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val TinyBarColorScheme = lightColorScheme(
    primary = Color(0xFF3385FF),
    onPrimary = Color.White,
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF111111),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFF6F8FC),
    onSurfaceVariant = Color(0xFF6B7280),
    outline = Color(0xFFE8EDF5)
)

private val TinyBarShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(16.dp)
)

@Composable
fun TinyBarTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = TinyBarColorScheme,
        shapes = TinyBarShapes,
        typography = MaterialTheme.typography,
        content = content
    )
}