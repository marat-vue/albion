#!/usr/bin/env python3
"""
Готовит трек для «Аудио с текстом» в Albion: запись LibriVox + текст Project Gutenberg
+ пословное выравнивание (stable-ts, MIT). Результат кладётся в <материалы>/listening/<id>/
и вшивается в APK тем же механизмом, что audio/ и collections/ (albion.bundleContent).

    python make_track.py saki-open-window            # один трек из tracks.json
    python make_track.py --all                       # все треки каталога
    python make_track.py --list                      # что есть в каталоге

Нужно: pip install stable-ts imageio-ffmpeg requests (torch ставится вместе со stable-ts;
для CPU: pip install torch --index-url https://download.pytorch.org/whl/cpu).

Как выравниваем: whisper (через stable-ts) распознаёт запись с пословными метками времени,
затем распознанные слова сопоставляются с эталонным текстом Gutenberg (difflib). Так
вступление LibriVox («This is a LibriVox recording…») и мелкие оговорки чтеца не сбивают
разметку, а в приложение попадает авторский текст, а не распознанный. Слова без пары
получают время интерполяцией между соседями.

Модели MMS / ctc-forced-aligner не используются (CC-BY-NC), aeneas — тоже (AGPL).
"""
from __future__ import annotations

import argparse
import difflib
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

# Консоль Windows по умолчанию в cp1252 — без этого не печатается кириллица.
sys.stdout.reconfigure(encoding="utf-8")

HERE = Path(__file__).resolve().parent
DEFAULT_CONTENT = HERE.parents[2]  # AlbionApp/scripts/listening → корень репозитория
CACHE = HERE / ".cache"
LIBRIVOX_API = "https://librivox.org/api/feed/audiobooks/?id={id}&format=json&extended=1"
GUTENBERG_TXT = "https://www.gutenberg.org/cache/epub/{id}/pg{id}.txt"
LICENSE = "Public domain"
SAMPLE_RATE = 16000
# Длинные предложения режем по знакам препинания: фраза для диктанта и шэдоуинга должна помещаться в память.
MAX_SEGMENT_WORDS = 24
ABBREVIATIONS = ("Mr.", "Mrs.", "Dr.", "St.", "Messrs.", "Mme.", "Esq.", "Capt.", "Col.", "Prof.")


def fetch(url: str, target: Path) -> Path:
    import requests

    if target.exists() and target.stat().st_size > 0:
        return target
    target.parent.mkdir(parents=True, exist_ok=True)
    print(f"  ↓ {url}")
    with requests.get(url, stream=True, timeout=60, headers={"User-Agent": "albion-listening/1.0"}) as r:
        r.raise_for_status()
        tmp = target.with_suffix(target.suffix + ".part")
        with open(tmp, "wb") as f:
            for chunk in r.iter_content(1 << 16):
                f.write(chunk)
        tmp.replace(target)
    return target


def librivox_section(book_id: int, section: int) -> dict:
    path = fetch(LIBRIVOX_API.format(id=book_id), CACHE / f"librivox-{book_id}.json")
    book = json.loads(path.read_text(encoding="utf-8"))["books"][0]
    for s in book["sections"]:
        if int(s["section_number"]) == section:
            return {"book": book, "section": s}
    raise SystemExit(f"В книге LibriVox {book_id} нет раздела {section}")


def gutenberg_story(book_id: int, start: str, end: str | None) -> str:
    raw = fetch(GUTENBERG_TXT.format(id=book_id), CACHE / f"pg{book_id}.txt").read_text(encoding="utf-8-sig")
    body_start = raw.find("*** START OF")
    body_end = raw.find("*** END OF")
    body = raw[raw.index("\n", body_start) + 1 if body_start >= 0 else 0: body_end if body_end >= 0 else None]
    # Заголовок ищем как отдельную строку. В оглавлении он тоже есть, но там до следующего
    # заголовка одна строка — поэтому берём вхождение с самым длинным текстом до [end].
    lines = body.splitlines()
    starts = [i for i, l in enumerate(lines) if l.strip().upper() == start.upper()]
    if not starts:
        raise SystemExit(f"Не найден заголовок «{start}» в Gutenberg #{book_id}")
    spans = [(i, _story_end(lines, i, end)) for i in starts]
    begin, stop = max(spans, key=lambda span: span[1] - span[0])
    if stop <= begin + 1:
        raise SystemExit(f"Не найден конец рассказа «{end}» после «{start}» в Gutenberg #{book_id}")
    return normalize_text("\n".join(lines[begin + 1: stop]))


def _story_end(lines: list[str], i: int, end: str | None) -> int:
    if end:
        for j in range(i + 1, len(lines)):
            if lines[j].strip().upper().startswith(end.upper()):
                return j
        return i
    return len(lines)


def normalize_text(text: str) -> str:
    """Склеивает строки абзацев, убирает разметку Gutenberg (_курсив_, [Illustration]), сноски и лишние пробелы."""
    text = re.sub(r"\[(Illustration|Footnote)[^\]]*\]", " ", text, flags=re.S)
    text = text.replace("_", "")
    paragraphs = [re.sub(r"\s+", " ", p).strip() for p in re.split(r"\n\s*\n", text)]
    paragraphs = [p for p in paragraphs if p and not re.fullmatch(r"[*\s.]+", p)]
    text = "\n\n".join(paragraphs)
    text = text.replace("--", "—").replace(" — ", " — ")
    return text.strip()


def split_segments(text: str) -> list[str]:
    """Абзацы → предложения → фразы не длиннее MAX_SEGMENT_WORDS слов."""
    out: list[str] = []
    for para in text.split("\n\n"):
        protected = para
        for a in ABBREVIATIONS:
            protected = protected.replace(a, a.replace(".", "\u0000"))
        sentences = re.split(r"(?<=[.!?])([\"”’)]*)\s+(?=[\"“‘(]?[A-Z])", protected)
        # re.split с группой возвращает и хвосты кавычек — приклеиваем их к предыдущему предложению.
        merged: list[str] = []
        for k, piece in enumerate(sentences):
            if k % 2 == 1:
                merged[-1] += piece
            elif piece:
                merged.append(piece)
        for s in merged:
            out.extend(_split_long(s.replace("\u0000", ".").strip()))
    return [s for s in out if s]


def _split_long(sentence: str) -> list[str]:
    words = sentence.split()
    if len(words) <= MAX_SEGMENT_WORDS:
        return [sentence]
    best, best_score = None, None
    for m in re.finditer(r"[;:—,]\s", sentence):
        left = len(sentence[: m.end()].split())
        if left < 5 or len(words) - left < 5:
            continue
        score = abs(left - len(words) / 2) - (6 if m.group()[0] in ";:—" else 0)
        if best_score is None or score < best_score:
            best, best_score = m.end(), score
    if best is None:
        return [sentence]
    return _split_long(sentence[:best].strip()) + _split_long(sentence[best:].strip())


def word_key(w: str) -> str:
    return re.sub(r"[^a-z0-9']", "", w.lower().replace("’", "'"))


def load_audio(mp3: Path):
    import numpy as np
    import imageio_ffmpeg

    cmd = [imageio_ffmpeg.get_ffmpeg_exe(), "-nostdin", "-i", str(mp3), "-f", "s16le", "-ac", "1",
           "-ar", str(SAMPLE_RATE), "-loglevel", "error", "-"]
    pcm = subprocess.run(cmd, check=True, capture_output=True).stdout
    return np.frombuffer(pcm, np.int16).astype(np.float32) / 32768.0


def recognise(mp3: Path, model_name: str, device: str | None) -> list[tuple[str, int, int]]:
    """Слова записи с метками времени (мс). Результат кэшируется рядом с mp3."""
    cache = mp3.with_suffix(f".{model_name}.words.json")
    if cache.exists():
        return [tuple(w) for w in json.loads(cache.read_text(encoding="utf-8"))]
    import stable_whisper

    print(f"  ⌛ распознаю {mp3.name} моделью {model_name} — на CPU это несколько минут")
    model = stable_whisper.load_model(model_name, device=device)
    result = model.transcribe(load_audio(mp3), language="en", word_timestamps=True, vad=False, verbose=None)
    words = [(w.word.strip(), int(w.start * 1000), int(w.end * 1000)) for seg in result.segments for w in seg.words]
    cache.write_text(json.dumps(words), encoding="utf-8")
    return words


def align(segments: list[str], heard: list[tuple[str, int, int]], partial: bool) -> list[dict]:
    ref = [(si, w) for si, seg in enumerate(segments) for w in seg.split()]
    ref_keys = [word_key(w) for _, w in ref]
    hyp_keys = [word_key(w) for w, _, _ in heard]
    times: list[tuple[int, int] | None] = [None] * len(ref)
    matcher = difflib.SequenceMatcher(None, ref_keys, hyp_keys, autojunk=False)
    for block in matcher.get_matching_blocks():
        for k in range(block.size):
            _, s, e = heard[block.b + k]
            times[block.a + k] = (s, e)
    matched = [i for i, t in enumerate(times) if t]
    if len(matched) < len(ref) * (0.3 if partial else 0.6):
        raise SystemExit(f"Совпало только {len(matched)} из {len(ref)} слов — проверьте start/end в tracks.json")
    last = matched[-1]
    if partial:
        # Запись — часть рассказа: текст после последнего узнанного предложения отрезаем.
        cut_seg = ref[last][0]
        keep = sum(1 for si, _ in ref if si <= cut_seg)
        ref, times = ref[:keep], times[:keep]
        segments = segments[: cut_seg + 1]
    _interpolate(times)
    out = []
    for si, seg in enumerate(segments):
        words = []
        for (ws, w), t in zip(ref, times):
            if ws != si:
                continue
            clean = w.strip("\"“”‘’'()[],.;:!?—-")
            if clean and t:
                words.append({"s": t[0], "e": max(t[1], t[0] + 40), "w": clean})
        if words:
            out.append({"s": words[0]["s"], "e": words[-1]["e"], "text": seg, "words": words})
    return out


def _interpolate(times: list) -> None:
    """Слова без пары получают долю промежутка между соседями пропорционально позиции."""
    n = len(times)
    i = 0
    while i < n:
        if times[i] is not None:
            i += 1
            continue
        j = i
        while j < n and times[j] is None:
            j += 1
        left = times[i - 1][1] if i > 0 else (times[j][0] if j < n else 0)
        right = times[j][0] if j < n else left + 400 * (j - i)
        step = max(right - left, 0) / (j - i)
        for k in range(i, j):
            times[k] = (int(left + step * (k - i)), int(left + step * (k - i + 1)))
        i = j


def make_track(entry: dict, content: Path, model: str, device: str | None) -> None:
    print(f"▶ {entry['id']}: {entry['title']}")
    lv = librivox_section(entry["librivox_book"], entry["section"])
    book, section = lv["book"], lv["section"]
    reader = ", ".join(r["display_name"] for r in section.get("readers", []))
    mp3 = fetch(section["listen_url"].replace("http://", "https://"), CACHE / f"{entry['id']}.mp3")
    text = gutenberg_story(entry["gutenberg"], entry["start"], entry.get("end"))
    segments = split_segments(text)
    heard = recognise(mp3, model, device)
    aligned = align(segments, heard, entry.get("partial", False))

    target = content / "listening" / entry["id"]
    target.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(mp3, target / "audio.mp3")
    (target / "transcript.json").write_text(json.dumps(aligned, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    meta = {
        "id": entry["id"],
        "title": entry["title"],
        "author": entry["author"],
        "reader": reader,
        "accent": "British",
        "level": entry.get("level", ""),
        "source": "LibriVox",
        "sourceUrl": book.get("url_librivox", ""),
        "textSource": f"Project Gutenberg #{entry['gutenberg']}",
        "license": LICENSE,
        "attribution": f"LibriVox, public domain. Читает {reader}. Текст — Project Gutenberg.",
        "durationMs": int(section.get("playtime", 0)) * 1000,
    }
    (target / "track.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"  ✓ {len(aligned)} фраз → {target}")


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("ids", nargs="*", help="id треков из tracks.json")
    p.add_argument("--all", action="store_true", help="все треки каталога")
    p.add_argument("--list", action="store_true", help="показать каталог")
    p.add_argument("--catalog", type=Path, default=HERE / "tracks.json")
    p.add_argument("--content", type=Path, default=DEFAULT_CONTENT, help="корень материалов (albion.contentDir)")
    p.add_argument("--model", default="base.en", help="модель whisper: tiny.en, base.en, small.en…")
    p.add_argument("--device", default=None, help="cpu / cuda (по умолчанию — что доступно)")
    args = p.parse_args()

    catalog = json.loads(args.catalog.read_text(encoding="utf-8"))
    if args.list:
        for e in catalog:
            print(f"{e['id']:34} {e['title']} — {e['reader_check']}")
        return
    chosen = catalog if args.all else [e for e in catalog if e["id"] in args.ids]
    if not chosen:
        p.error("укажите id трека или --all (см. --list)")
    for entry in chosen:
        make_track(entry, args.content, args.model, args.device)


if __name__ == "__main__":
    main()
