---
paths:
  - "**/*.kt"
  - "**/*.kts"
---
# Kotlin в Albion

Адаптировано из ECC `rules/kotlin` под этот проект.

## Стиль

- `val` по умолчанию; `data class` для значений; наружу отдавать `List`/`Map`/`Set`, не `Mutable*`.
- Обновление состояния — копированием: `_state.update { it.copy(...) }`, не мутацией коллекций внутри `StateFlow`.
- В новом коде избегать `!!`: `?.`, `?:`, `requireNotNull`/`checkNotNull` с сообщением.
- Закрытые иерархии — `sealed interface` и исчерпывающий `when` без `else`.
- Вспомогательное — top-level функции и расширения, не утилитные классы.
- Не более двух уровней вложенности scope-функций.

## Корутины и Flow

- Никакого `GlobalScope`: `viewModelScope` или переданный структурированный scope.
- `CancellationException` всегда пробрасывать; `runCatching` вокруг suspend-вызовов — только с перебросом отмены.
- Room, файлы, разбор xlsx/epub/pdf, нативный движок — через `withContext(Dispatchers.IO)` / `Default`.
- `stateIn(..., SharingStarted.WhileSubscribed(5_000), ...)` для экранного состояния, если нет причины держать поток горячим.

## Compose

- Побочные эффекты — в `LaunchedEffect`/`DisposableEffect` или во ViewModel, не в теле composable.
- У элементов `LazyColumn`/`LazyRow` — стабильные `key`.
- `remember` — с ключами всех зависимостей.
- Не передавать навигационные объекты вглубь — передавать лямбды.
- Формы, цвета, шрифты — только из темы и компонентов «тёплого редакционного» стиля (см. `CLAUDE.md`, `AlbionApp/DESIGN.md`).

## Тесты

- JUnit 4 (`app/src/test`), инструментальные — `app/src/androidTest`.
- Фейки вместо моков; Room — `Room.inMemoryDatabaseBuilder()`.
- Имена тестов — описательные, в обратных кавычках.
