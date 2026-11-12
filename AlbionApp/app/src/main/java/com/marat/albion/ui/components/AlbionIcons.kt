package com.marat.albion.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Собственный набор иконок Albion: сетка 24, линия 1.6, прямые концы и острые углы.
 * У вкладок есть пары «контур / заливка» для выбранного состояния.
 */
object AlbionIcons {
    val Hourglass = pen("Hourglass", "M6 3h12 M6 21h12 M7 3v4l5 5-5 5v4 M17 3v4l-5 5 5 5v4")
    private const val STROKE = 1.6f

    private fun glyph(name: String, stroke: String? = null, fill: String? = null, mirror: Boolean = false): ImageVector {
        val b = ImageVector.Builder(
            name = "Albion.$name", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = mirror,
        )
        if (fill != null) b.addPath(pathData = PathParser().parsePathString(fill).toNodes(), fill = SolidColor(Color.Black))
        if (stroke != null) b.addPath(
            pathData = PathParser().parsePathString(stroke).toNodes(),
            fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = STROKE,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        return b.build()
    }

    private fun pen(name: String, data: String, mirror: Boolean = false) = glyph(name, stroke = data, mirror = mirror)

    // ---- вкладки: контур и заливка ----
    private const val CARD = "M4 9.5h16v10.5H4z"
    val Layers = pen("Today", "M8 4h8 M6 6.75h12 $CARD")
    val LayersFilled = glyph("TodayFilled", stroke = "M8 4h8 M6 6.75h12", fill = CARD)

    private const val BOOK = "M12 6.5C10 5 7.5 4.5 3.75 4.75v13.5c3.75-.25 6.25.25 8.25 1.75 2-1.5 4.5-2 8.25-1.75V4.75C16.5 4.5 14 5 12 6.5z"
    val MenuBook = pen("Grammar", "$BOOK M12 6.5V20")
    val MenuBookFilled = glyph("GrammarFilled",
        fill = "M11.2 6.05C9.3 4.85 7 4.45 3.75 4.6v13.65c3.3-.15 5.6.3 7.45 1.45z M12.8 6.05c1.9-1.2 4.2-1.6 7.45-1.45v13.65c-3.3-.15-5.6.3-7.45 1.45z")

    private const val COVER = "M4.75 3.75h14.5v12.5H4.75z"
    val AutoStories = pen("Reading", "$COVER M4.75 16.25v4h14.5 M9 3.75v5.5l1.75-1.25 1.75 1.25v-5.5")
    val AutoStoriesFilled = glyph("ReadingFilled", stroke = "M4.75 16.25v4h14.5", fill = COVER)

    private const val PENCIL = "M14.75 19.25l-3.25.75.75-3.25 6.9-6.9a1.77 1.77 0 0 1 2.5 2.5z"
    val EditNote = pen("Practice", "M3.75 6h10 M3.75 11h6.5 M3.75 16h4 $PENCIL")
    val EditNoteFilled = glyph("PracticeFilled", stroke = "M3.75 6h10 M3.75 11h6.5 M3.75 16h4", fill = PENCIL)

    private const val BARS = "M4.75 12h3.5v8.25h-3.5z " +
        "M10.25 7.75h3.5v12.5h-3.5z " +
        "M15.75 3.75h3.5v16.5h-3.5z"
    val Insights = pen("Progress", BARS)
    val InsightsFilled = glyph("ProgressFilled", fill = BARS)

    // ---- действия ----
    val Settings = pen("Settings", "M4 7.5h9 M19 7.5h1 M4 16.5h1 M11 16.5h9 M16 5.25a2.25 2.25 0 1 1 0 4.5 2.25 2.25 0 0 1 0-4.5z M8 14.25a2.25 2.25 0 1 1 0 4.5 2.25 2.25 0 0 1 0-4.5z")
    val Moon = pen("Moon", "M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z")
    val Sun = pen("Sun", "M12 8a4 4 0 1 1 0 8 4 4 0 0 1 0-8z M12 2.75v1.5 M12 19.75v1.5 M2.75 12h1.5 M19.75 12h1.5 M5.5 5.5l1.05 1.05 M17.45 17.45l1.05 1.05 M5.5 18.5l1.05-1.05 M17.45 6.55l1.05-1.05")
    val ArrowForward = pen("Forward", "M4.5 12h15 M13.5 6l6 6-6 6", true)
    val ArrowBack = pen("Back", "M19.5 12h-15 M10.5 6l-6 6 6 6", true)
    val East = ArrowForward
    val West = ArrowBack
    val Close = pen("Close", "M6.5 6.5l11 11 M17.5 6.5l-11 11")
    val Clear = Close
    val Check = pen("Check", "M4.5 12.5l4.75 4.75L19.5 7")
    val TaskAlt = pen("Mastered", "M20 11v1a8 8 0 1 1-4-7 M9 10l3 3 8-9")
    val Add = pen("Add", "M12 5v14 M5 12h14")
    val Search = pen("Search", "M17 10.5a6.5 6.5 0 1 1-13 0 6.5 6.5 0 0 1 13 0 M15.25 15.25l4.75 4.75")
    val BookmarkBorder = pen("Bookmark", "M6 3h12v18l-6-4-6 4z")
    val Bookmark = pen("Saved", "M6 3h12v18l-6-4-6 4z M9 9l2 2 4-4")
    val Delete = pen("Delete", "M4 6h16 M9 6V3h6v3 M6 6l1 15h10l1-15 M10 10v7 M14 10v7")
    val Bookmarks = pen("Bookmarks", "M5 6h12v16l-6-4-6 4z M9 2h12v16")
    val VolumeUp = pen("Listen", "M3 9h4l5-5v16l-5-5H3z M16 8c2 2 2 6 0 8 M19 5c4 4 4 10 0 14", true)
    val Mic = pen("Microphone", "M9 5a3 3 0 0 1 6 0v7a3 3 0 0 1-6 0z M5 11v1a7 7 0 0 0 14 0v-1 M12 19v3 M8 22h8")
    val RecordVoiceOver = pen("Speech", "M8 9a3 3 0 1 1 0-6 3 3 0 0 1 0 6 M2 21v-3a6 6 0 0 1 12 0v3 M17 5c3 2 3 6 0 8 M20 2c5 4 5 10 0 14")
    /** Ухо: «Минимальные пары» — различать звуки на слух. */
    val Hearing = pen("Hearing", "M6 9a6 6 0 0 1 12 0c0 4-4 5-4 9a3 3 0 0 1-6 0 M9.5 9a2.5 2.5 0 0 1 5 0c0 1.5-1.5 2-2.5 3")
    val PlayArrow = pen("Play", "M8 4.5v15L19.5 12z")
    val Stop = pen("Stop", "M6.5 6.5h11v11h-11z")
    val SlowMotionVideo = pen("Slow", "M20 12a8 8 0 1 1-8-8 M12 1v6 M18 3l-3 4 M23 7l-5 2 M10 9l5 3-5 3z")
    val Undo = pen("Undo", "M8 4L3 9l5 5 M3 9h11a6 6 0 0 1 0 12", true)
    val Redo = pen("Redo", "M16 4l5 5-5 5 M21 9H10a6 6 0 0 0 0 12", true)
    val History = pen("History", "M3 10a9 9 0 1 1 2 8 M3 4v6h6 M12 7v6l4 2")
    val ExpandMore = pen("Expand", "M6 9l6 6 6-6")
    val KeyboardArrowDown = ExpandMore
    val Menu = pen("Contents", "M4 6h16 M4 12h16 M4 18h16")
    val List = pen("List", "M8 5h13 M8 12h13 M8 19h13 M3 5h1 M3 12h1 M3 19h1", true)
    val MoreHoriz = pen("More", "M4 12h1 M11.5 12h1 M19 12h1")
    val Info = pen("Information", "M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0 M12 11v6 M12 7h.01")
    val HelpOutline = pen("Help", "M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0 M9 9a3 3 0 1 1 5 2c-2 1-2 2-2 3 M12 17h.01")
    val Lightbulb = pen("Hint", "M8 15c0-2-3-3-3-7a7 7 0 0 1 14 0c0 4-3 5-3 7 M8 18h8 M10 21h4 M12 5v6")
    val Route = pen("Path", "M6 7a2 2 0 1 1 0-4 2 2 0 0 1 0 4 M18 21a2 2 0 1 1 0-4 2 2 0 0 1 0 4 M6 7v5h12v5")
    val Timeline = pen("Timeline", "M3 17l6-7 5 3 7-9 M3 17h.01 M9 10h.01 M14 13h.01 M21 4h.01")
    val Keyboard = pen("Keyboard", "M2 5h20v14H2z M6 9h1 M11 9h1 M16 9h1 M6 12h1 M11 12h1 M16 12h1 M7 16h10")
    val GridView = pen("Grid", "M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z")
    val ViewWeek = pen("Columns", "M3 4h18v16H3z M9 4v16 M15 4v16")
    val Rule = pen("Rule", "M3 6l2 2 4-4 M13 6h8 M3 17l5-5 M3 12l5 5 M13 15h8", true)
    val CheckBox = pen("Checked", "M19 12v8H3V4h11 M8 10l4 4L21 3")
    val CheckBoxOutlineBlank = pen("Unchecked", "M3 4h17v16H3z")
    val BorderColor = pen("Highlight", "M7 15l-3 4h7L22 8l-7-7L4 12z M12 4l7 7 M2 23h20")
    val TextFields = pen("Type", "M2 5h12 M8 5v15 M4 20h8 M14 10h8 M18 10v10 M15 20h6")
    val FormatQuote = pen("Quote", "M3 5h6v7H5c0 3 1 4 4 5v2c-5-1-6-4-6-7z M15 5h6v7h-4c0 3 1 4 4 5v2c-5-1-6-4-6-7z")
    val CloudOff = pen("Offline", "M5 17a4 4 0 0 1-1-8 7 7 0 0 1 12-4 M18 9a4 4 0 0 1 2 8 M2 2l20 20")
    val ReportProblem = pen("Attention", "M12 3l10 18H2z M12 9v5 M12 17h.01")
    val Inventory2 = pen("Collection", "M3 4h18v5H3z M5 9v12h14V9 M9 13h6")

    // ---- разговор с ИИ: свой знак у каждой ситуации ----
    val Chat = pen("Chat", "M4 5.5h16v10H9l-5 4z M8 9.5h8 M8 12.5h5")
    val Coffee = pen("Cafe", "M5 9h11v4.5A5.5 5.5 0 0 1 10.5 19 5.5 5.5 0 0 1 5 13.5z M16 10.5h1.5a2.25 2.25 0 0 1 0 4.5h-2 M8.5 3.5V6 M12 3.5V6")
    val Bed = pen("Hotel", "M3 18.5V6 M3 14h18v4.5 M21 14v-2.5A2.5 2.5 0 0 0 18.5 9H11v5 M5.25 11a1.75 1.75 0 1 0 3.5 0 1.75 1.75 0 1 0-3.5 0")
    val Briefcase = pen("Interview", "M3.75 8h16.5v11H3.75z M9 8V5.75h6V8 M3.75 13h16.5")
    val Health = pen("Doctor", "M12 20.5s-7.5-4.5-7.5-10A4.25 4.25 0 0 1 12 7.75a4.25 4.25 0 0 1 7.5 2.75c0 5.5-7.5 10-7.5 10z M12 10.5v4.5 M9.75 12.75h4.5")
    val Bag = pen("Shop", "M5 8h14l-1 12H6z M9 8V6.5a3 3 0 0 1 6 0V8")
    val Debate = pen("Opinion", "M3.5 4.5h11v7H8l-4.5 3.5z M17.5 8.5h3v6h-3v3l-4-3h-3")
}
