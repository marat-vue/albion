package com.marat.albion.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Самая широкая колонка, в которой удобно читать списки и текст. */
val ReadableWidth = 760.dp

/** Главная на планшете: две колонки по ширине почти как у [ReadableWidth]. */
val DashboardWidth = 1200.dp

/** Колонка для одного задания: карточка, мини-игра, тест. */
val FocusWidth = 680.dp

/**
 * На широком экране экран остаётся колонкой по центру: строки не растягиваются
 * на всю ширину планшета. На телефоне ограничение ни на что не влияет.
 */
@Composable
fun CenteredColumn(maxWidth: Dp, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        MeasureNarrow(Modifier.widthIn(max = maxWidth).fillMaxSize()) { content() }
    }
}

/**
 * Высота стеклянной панели вкладок (вместе с системной навигацией), под которую уходит контент.
 * Экраны с вкладками добавляют её к нижнему отступу прокрутки; без панели — 0.
 */
val LocalBottomChrome = androidx.compose.runtime.compositionLocalOf { 0.dp }

val bottomChrome: Dp
    @Composable get() = LocalBottomChrome.current

/** Узкий экран (до 360 dp): поля и крупный текст ужимаются, чтобы содержимому хватило места. */
val LocalNarrow = androidx.compose.runtime.compositionLocalOf { false }

fun isNarrow(width: Dp) = width < 360.dp

/** Боковое поле экрана: 24 dp, на узких телефонах — 16 dp. */
val gutter: Dp
    @Composable get() = if (LocalNarrow.current) 16.dp else 24.dp

/**
 * Меряет доступную ширину и сообщает вложенным элементам, узкий ли экран ([LocalNarrow]).
 * Ширина берётся из раскладки, а не из конфигурации, — так колонка планшета и тесты
 * с узким контейнером ведут себя одинаково.
 */
@Composable
fun MeasureNarrow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        androidx.compose.runtime.CompositionLocalProvider(LocalNarrow provides isNarrow(maxWidth)) { content() }
    }
}
