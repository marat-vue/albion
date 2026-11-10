package com.marat.albion.ui.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marat.albion.LocalAppContainer
import com.marat.albion.data.content.Deck
import com.marat.albion.data.db.CardEntity
import com.marat.albion.data.db.CollectionEntity
import com.marat.albion.data.repo.CollectionSummary
import com.marat.albion.data.repo.StudySource
import com.marat.albion.ui.components.AlbionIcons
import com.marat.albion.ui.components.BoundVolume
import com.marat.albion.ui.components.DestructiveConfirmationDialog
import com.marat.albion.ui.components.Eyebrow
import com.marat.albion.ui.components.GhostButton
import com.marat.albion.ui.components.Hairline
import com.marat.albion.ui.components.IconButton
import com.marat.albion.ui.components.LevelBadge
import com.marat.albion.ui.components.LoadingState
import com.marat.albion.ui.components.PaneDropdownMenu
import com.marat.albion.ui.components.Panel
import com.marat.albion.ui.components.PrimaryButton
import com.marat.albion.ui.components.RoundIconButton
import com.marat.albion.ui.components.ScrollableSheet
import com.marat.albion.ui.components.SectionHeader
import com.marat.albion.ui.components.TopBar
import com.marat.albion.ui.components.gutter
import com.marat.albion.ui.components.selectedRow
import com.marat.albion.ui.deck.DeckStudyMetrics
import com.marat.albion.ui.library.pluralRu
import com.marat.albion.ui.nav.BrowseRoute
import com.marat.albion.ui.nav.CardRoute
import com.marat.albion.ui.nav.Navigator
import com.marat.albion.ui.nav.StudyRoute
import com.marat.albion.ui.theme.appear
import com.marat.albion.ui.theme.selectedContent
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
fun CollectionScreen(id: Long, navigator: Navigator) {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val loaded by remember(id) { c.collections.observe(id).map { true to it } }.collectAsStateWithLifecycle(false to null)
    val collection = loaded.second
    val summary by remember(id) { c.study.observeOverview().map { o -> o.collections.firstOrNull { it.collection.id == id } } }
        .collectAsStateWithLifecycle(null)
    val words by remember(id) { c.study.observeCollectionWords(id) }.collectAsStateWithLifecycle(null)
    var menu by remember { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var pickDictionary by rememberSaveable { mutableStateOf(false) }
    // Коллекцию удалили — возвращаемся к списку.
    LaunchedEffect(loaded) { if (loaded.first && collection == null) navigator.back() }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar(onBack = navigator::back) {
            if (collection != null) Box {
                RoundIconButton(AlbionIcons.MoreHoriz, "Ещё", { menu = true }, filled = true)
                PaneDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Переименовать") }, onClick = { menu = false; renaming = true })
                    DropdownMenuItem(text = { Text("Удалить коллекцию") }, onClick = { menu = false; confirmDelete = true },
                        colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.error))
                }
            }
        }
        if (collection == null || words == null) {
            LoadingState("Открываем коллекцию")
            return@Column
        }
        val list = words.orEmpty()
        val shownCard = (navigator.shownDetail as? CardRoute)?.cardId
        LazyColumn(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars),
            contentPadding = PaddingValues(bottom = 24.dp)) {
            item { CollectionHeader(collection, list.size, Modifier.padding(horizontal = gutter)) }
            item {
                Column(Modifier.padding(horizontal = gutter)) {
                    if (list.isEmpty()) EmptyCollection()
                    else summary?.let { StudyBlock(it) { navigator.open(StudyRoute(StudySource.Collection(id))) } }
                    Spacer(Modifier.height(12.dp))
                    GhostButton("Добавить слова из словаря", { pickDictionary = true }, icon = AlbionIcons.Add,
                        modifier = Modifier.appear(3))
                }
            }
            if (list.isNotEmpty()) {
                item {
                    SectionHeader("Слова", Modifier.padding(horizontal = gutter), trailing = "${list.size}")
                }
                items(list, key = { it.id }) { card ->
                    CollectionWordRow(card, selected = card.id == shownCard,
                        onOpen = { navigator.openDetail(CardRoute(card.id)) },
                        onRemove = { scope.launch { runCatching { c.collections.remove(id, card) } } })
                }
            }
        }
    }

    if (pickDictionary) ScrollableSheet("Откуда добавить слова", { pickDictionary = false }) {
        Text("Отметьте слова в словаре — они сразу попадут в коллекцию.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Deck.entries.forEach { deck ->
            Panel(Modifier.fillMaxWidth(), onClick = { pickDictionary = false; navigator.open(BrowseRoute(deck, collectionId = id)) },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(deck.title, style = MaterialTheme.typography.titleMedium)
                        Text(deck.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(AlbionIcons.ArrowForward, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (renaming && collection != null) {
        CollectionNameDialog("Переименовать коллекцию", "Сохранить", { renaming = false }, initial = collection.name) { name ->
            c.collections.rename(id, name)
            renaming = false
        }
    }
    if (confirmDelete && collection != null) {
        DestructiveConfirmationDialog(
            title = "Удалить коллекцию?",
            message = "Коллекция «${collection.name}» будет удалена. Слова останутся в словарях, их прогресс и расписание повторений сохранятся.",
            onConfirm = { confirmDelete = false; scope.launch { c.collections.delete(id) } },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun CollectionHeader(collection: CollectionEntity, count: Int, modifier: Modifier) {
    Column(modifier) {
        Eyebrow("Коллекция")
        Spacer(Modifier.height(12.dp))
        Text(collection.name, style = if (com.marat.albion.ui.components.LocalNarrow.current) MaterialTheme.typography.displaySmall
            else MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(8.dp))
        Text(if (count == 0) "Пока без слов" else "$count ${pluralRu(count, "слово", "слова", "слов")}",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StudyBlock(summary: CollectionSummary, onStart: () -> Unit) {
    BoundVolume(Modifier.fillMaxWidth().padding(top = 24.dp).appear(2)) {
        DeckStudyMetrics(summary.due, summary.newAvailable)
        PrimaryButton(
            text = if (summary.toStudy > 0) "Начать занятие" else "На сегодня всё",
            onClick = onStart,
            enabled = summary.toStudy > 0,
            trailingIcon = AlbionIcons.ArrowForward,
            inverse = true,
        )
        Text("Начато ${summary.started} из ${summary.total} · новые слова коллекции приходят и в занятие «Сегодня»",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun EmptyCollection() {
    Column(Modifier.padding(top = 24.dp)) {
        Text("Добавьте первые слова", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(10.dp))
        Text("Выберите словарь и отметьте нужные слова, идиомы или глаголы. Ещё слово можно добавить с его карточки.",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CollectionWordRow(card: CardEntity, selected: Boolean, onOpen: () -> Unit, onRemove: () -> Unit) {
    val content = if (selected) MaterialTheme.colorScheme.selectedContent else MaterialTheme.colorScheme.onSurface
    val secondary = if (selected) MaterialTheme.colorScheme.selectedContent else MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().selectedRow(selected).clickable(onClick = onOpen)) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(card.term, style = MaterialTheme.typography.titleMedium, color = content, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(10.dp))
                    LevelBadge(card.level)
                }
                Text(listOfNotNull(Deck.byId(card.deck)?.title, card.meaningRu.ifBlank { card.meaningEn }.takeIf { it.isNotBlank() })
                    .joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = secondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) {
                Icon(AlbionIcons.Close, "Убрать «${card.term}» из коллекции", Modifier.size(18.dp), tint = secondary)
            }
        }
        Hairline(Modifier.padding(horizontal = gutter))
    }
}
