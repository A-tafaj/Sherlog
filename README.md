# Sherlog

Desktop GUI tool (Kotlin + Compose Desktop) for analyzing and cleaning large
Android logcat files — a replacement for manual `findstr`/regex log cleanup.

## Features (MVP)

- Open `.txt` / `.log` logcat dumps of any size (1GB+); the file is **indexed,
  never loaded into memory** — line text is read from disk on demand.
- Parses threadtime format: timestamp, PID, TID, level, tag.
- Dashboard: total lines, errors, warnings, unique tags, filtered count.
- Top-tags list with counts, sortable by count or A–Z, searchable, with
  checkbox include-filtering.
- Filters: tags, PIDs, time range (`MM-DD HH:MM:SS`), levels,
  exclude-substrings, keep-substrings. All combinable.
- Search: case-insensitive, optional regex, highlighted matches, match count.
- Debug presets: Network / Crash / Video.
- Export the filtered view to a new `.txt`/`.log` file (streaming).
- All heavy work runs on background coroutines with progress + cancel.

## Architecture

```
parser/   LogcatLineParser      hand-rolled threadtime parser (no regex, hot path)
core/     LogIndexer            one streaming pass -> LogIndex
          LogIndex              per-line metadata in primitive arrays (~25 B/line)
          LineTextProvider      on-demand line text via 64KB block LRU cache
filter/   FilterState/Engine    metadata filters answered from the index (instant);
                                text filters stream the file once, cancellable
          Presets               Network / Crash / Video profiles
export/   LogExporter           streams selected lines to the output file
ui/       Compose Desktop UI    AppState + App/FilterPanel/LogViewer
```

Timestamps have no year in logcat, so they are stored as millis relative to a
fixed leap reference year (02-29 always parses). Lines that don't match the
threadtime format (buffer markers, raw stack-trace dumps) are kept with level
`UNKNOWN` / no tag; the "Other" level checkbox controls them.

## Run

```
.\gradlew.bat run
```

## Test

```
.\gradlew.bat test
```

## Package (Windows installer)

```
.\gradlew.bat packageMsi
```

Requires JDK 17+.
