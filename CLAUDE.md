# Albion

Android-приложение для изучения британского английского: карточки FSRS-6, учебник грамматики, читалка книг, упражнения, разговор с ИИ на устройстве, статистика. Общаемся по-русски.

## Структура

- `AlbionApp/` — приложение (Kotlin, Jetpack Compose, Material 3, Navigation 3, Room, DataStore, Media3, llama.cpp + whisper.cpp через NDK/CMake). Один модуль `app`, пакет `com.marat.albion`.
  - `data/` — логика и хранение: `db` (Room), `fsrs`, `repo` (StudyRepository, StudyPlanner, CollectionRepository), `grammar`, `library`, `conversation`, `speech`, `translation`, `content`, `settings`.
  - `ui/` — экраны Compose по разделам (`home`, `study`, `grammar`, `library`, `games`, `conversation`, `collections`, `stats`, `settings`…), навигация в `ui/nav/AlbionNavigation.kt`, тема в `ui/theme`, общие компоненты в `ui/components`.
  - `app/src/main/cpp` — JNI-обёртки над llama.cpp/whisper.cpp; исходники закреплены в `third_party/` (см. `third_party/LLAMA.md`, `WHISPER.md`).
- `collections/*.xlsx`, `audio/<колода>/*.mp3`, `grammar/*.md` — материалы; вшиваются в APK при сборке (`albion.bundleContent`, `albion.contentDir` в `gradle.properties`).
- `AlbionApp/README.md` — подробности о функциях и форматах материалов; `AlbionApp/DESIGN.md` — дизайн-система.

## Сборка и тесты

Из `AlbionApp/` (в PowerShell — `.\gradlew.bat`, аргументы с `=` брать в кавычки):

```
./gradlew assembleDebug
./gradlew assembleDebug -Palbion.bundleContent=false   # быстро, без материалов
./gradlew testDebugUnitTest                            # JVM-тесты: FSRS, разбор xlsx, грамматика
./gradlew connectedDebugAndroidTest                    # на эмуляторе/устройстве
```

Нужны SDK 37, NDK 30.0.16248370, CMake 3.31.6, JDK 17+. Для одной архитектуры: `-Palbion.abis=arm64-v8a`.

## Правила проекта

- **Дизайн «Mono»** (`DESIGN.md`): строгий монохром, все углы 0 dp — никаких `RoundedCornerShape(>0)`/`CircleShape`. Цвета только из схемы темы, без захардкоженных. Выбор = полная инверсия; контент на выбранной плашке — `selectedContent`, не `primary`. Единственный цвет — сигнал верно/ошибка. Шрифты: Geist, Geist Mono (подписи), Source Serif 4 (чтение), Noto Sans (IPA). Material `TextButton`/`IconButton` не использовать — обёртки из `ui/components/Buttons.kt`.
- **Текст интерфейса** — по-русски, на «вы»; строки пишутся прямо в Compose (так принято в проекте, `strings.xml` не используется).
- **Room**: схема версионируется через `AutoMigration` и `app/schemas/`. Любое изменение сущностей — новая версия БД + миграция; никакого `fallbackToDestructiveMigration`. Прогресс пользователя терять нельзя: id карточки = колода + термин + номер значения.
- **Обучение**: встроенные колоды — это «Словари» (справочники), слово «колода» в интерфейсе не возвращать. Новые слова в «Сегодня» берутся только из пользовательских коллекций; память FSRS одна на слово для всех источников (`StudySource`). Вкладки: Сегодня / Учебник / Чтение / Практика / Прогресс.
- **ИИ на устройстве**, бесплатно и офлайн, без платных API. Слот `Generation.CHAT` — беседа (рендер истории должен быть побайтно стабильным ради кэша), побочные задачи — только `Generation.TASK`.
- **Корутины**: не глотать `CancellationException`; IO — вне главного потока.

## Порядок работы

1. Перед заметной задачей — короткий план, затем код.
2. Логику покрывать JVM-тестами в `app/src/test` (рядом уже есть тесты на большинство областей); UI и Room — инструментальными в `app/src/androidTest`.
3. После изменений — `testDebugUnitTest`, а после правок Kotlin стоит прогнать агента `kotlin-reviewer`.
4. Сломалась сборка — агент `kotlin-build-resolver` (минимальные правки).
5. Новая учебная функция или спорное решение по методике — агент `learning-methodologist`.
