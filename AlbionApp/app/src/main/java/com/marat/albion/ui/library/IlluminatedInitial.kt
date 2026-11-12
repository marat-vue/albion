package com.marat.albion.ui.library

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.marat.albion.ui.theme.Heritage
import com.marat.albion.ui.theme.Hero

/** Буквица — графитовая плашка со скруглением 8 dp: графит на молоке, молоко на угле, без орнамента. */
internal fun DrawScope.drawIlluminatedInitial(frame: Rect, darkTheme: Boolean) {
    drawRoundRect(if (darkTheme) Heritage.Cream else Hero.Base, frame.topLeft, frame.size, CornerRadius(8.dp.toPx()))
}
