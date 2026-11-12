# Треки «Аудио с текстом»

`make_track.py` готовит трек для плеера в разделе «Чтение → Аудио с текстом»:
запись LibriVox (public domain) + текст Project Gutenberg + пословное выравнивание.
Всё делается на ПК; в приложение попадают готовые файлы, ИИ на телефоне для этого не нужен.

## Установка

```
python -m venv .venv
.venv\Scripts\python -m pip install torch --index-url https://download.pytorch.org/whl/cpu   # или CUDA-сборка
.venv\Scripts\python -m pip install stable-ts imageio-ffmpeg requests
```

ffmpeg ставить не нужно — используется бинарник из `imageio-ffmpeg`.

## Запуск

```
.venv\Scripts\python make_track.py --list               # каталог (tracks.json)
.venv\Scripts\python make_track.py saki-open-window     # один трек
.venv\Scripts\python make_track.py --all                # все
```

Параметры: `--model base.en` (по умолчанию; `small.en` точнее, `tiny.en` быстрее),
`--device cuda`, `--content <папка материалов>` (по умолчанию — корень репозитория, как `albion.contentDir=..`).

Результат — `listening/<id>/` в корне материалов:

| Файл | Что внутри |
|---|---|
| `audio.mp3` | запись LibriVox как есть (64 kbps, со вступлением чтеца) |
| `transcript.json` | `[{"s":мс,"e":мс,"text":"фраза","words":[{"s":мс,"e":мс,"w":"слово"}]}]` |
| `track.json` | название, автор, чтец, акцент, уровень, источник, лицензия, атрибуция |

Gradle-задача `syncAlbionContent` копирует `listening/*/{track.json,transcript.json,*.mp3}` в
`assets/content/listening` при `albion.bundleContent=true` — так же, как `audio/` и `collections/`.
Кэш загрузок и распознавания — `scripts/listening/.cache/` (в git не попадает).

## Как работает выравнивание

1. Whisper (через stable-ts, MIT) распознаёт запись с пословными метками времени.
2. Распознанные слова сопоставляются с текстом Gutenberg (`difflib`): в приложение попадает
   авторский текст, а не распознанный. Вступление LibriVox и оговорки чтеца не мешают.
3. Слова без пары (имена, редкие слова, которые whisper услышал иначе) получают время
   интерполяцией между соседями. Длинные предложения режутся по `; : — ,` до ~24 слов —
   фраза для диктанта и шэдоуинга должна помещаться в память.

Не используем: модели MMS и `ctc-forced-aligner` по умолчанию (CC-BY-NC), aeneas (AGPL).
Альтернатива для точной разметки — Montreal Forced Aligner (MIT) или WhisperX (BSD-2).

На CPU (`base.en`) рассказ на 8 минут обрабатывается примерно за 2–4 минуты.

## Каталог: британские чтецы

Акцент чтеца проверяется вручную по [таблице акцентов LibriVox](https://wiki.librivox.org/index.php/Accents_Table)
(страницы чтецов в каталоге LibriVox акцент не указывают). Авторы умерли до 1955 года.

| id | Рассказ | Чтец | Проверка |
|---|---|---|---|
| `saki-open-window` | Saki, *The Open Window* (8 мин) | Justin Brett | England › South East, «Conservative RP» |
| `saki-story-teller` | Saki, *The Story-Teller* (13 мин) | Justin Brett | то же |
| `saki-talking-out-of-tarrington` | Saki, *The Talking-Out of Tarrington* (7 мин) | Peter Yearsley | England › London, «South-east England, with some London colouring» |
| `saki-easter-egg` | Saki, *The Easter Egg* (10 мин) | Peter Yearsley | то же |
| `doyle-case-of-identity-1` | Conan Doyle, *A Case of Identity*, часть 1 (27 мин) | Ruth Golding | England › Kent, «RP/BBC English» |

В репозитории лежит только `saki-open-window` (≈3,8 МБ) — как пример и для теста
`ListeningTranscriptTest.bundled tracks parse`. Остальные собираются командой `--all`;
каждый трек добавляет к APK 3–13 МБ.

Отклонённые варианты: Phil Chenevert (Wilde, *The Happy Prince*) — американец;
Graham Redman, David Clarke — в таблице акцентов их нет, британский акцент не подтверждён.

### Добавить трек

1. Найдите книгу через API: `https://librivox.org/api/feed/audiobooks/?title=^Название&format=json&extended=1`
   — нужны `id` книги и `section_number` раздела.
2. Проверьте чтеца по таблице акцентов (или по его странице на форуме LibriVox).
3. Найдите номер книги на Gutenberg и заголовки рассказа и следующего за ним (как строки в `pgNNN.txt`).
4. Добавьте запись в `tracks.json` (`partial: true` — если запись покрывает только часть текста).
5. VOA Learning English (public domain, американский английский) можно добавить вручную:
   `"accent": "American"` в `track.json` — в списке появится пометка об акценте.

## Своя запись в приложении

Пользователь может добавить аудиофайл с субтитрами SRT или LRC (кнопка «Добавить свою запись»).
У SRT/LRC время только по фразам, поэтому подсвечивается фраза целиком; «расширенный» LRC
с `<mm:ss.xx>` перед словами даёт и пословную подсветку.
