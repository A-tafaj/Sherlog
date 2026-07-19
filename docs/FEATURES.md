# Sherlog — Feature List

## File handling
- Opens `.txt` / `.log` Android logcat dumps (threadtime format).
- No practical size limit: files are indexed in one streaming pass and never
  loaded into memory (~25 bytes of metadata per line; text stays on disk).
- Handles LF and CRLF endings, missing final newline, lines of any length,
  and non-logcat lines mixed in (markers, raw stack traces).
- Indexing shows progress and is cancellable. 147 MB / 2M lines ≈ 0.6 s.

## Parsing
- Extracts timestamp, PID, TID, level (V/D/I/W/E/F) and tag from every line.
- Two timestamp formats: classic logcat (`07-12 14:10:14.880`) and
  year-prefixed cached logs (`2026-07-14 14:27:36.530`), with
  correct ordering across month/year boundaries.
- Lines that don't match the format are kept with level "Other" and inherit
  the previous line's timestamp so stack traces stay with their crash.

## Dashboard
- Total lines, Errors (incl. Fatal), Warnings, Unique tags, Filtered count —
  updates live with the filters.

## Tag analysis
- Full tag list with per-tag line counts.
- Sort by count or alphabetically; search box to find tags.
- Checkbox selection with two modes:
  - **Show only checked** — whitelist.
  - **Hide checked** — blacklist (instant noise muting).
  - Mode flip preserves checks (acts as invert).

## Filtering (all combinable, auto-applied, cancellable)
- **Tags** (show-only / hide, from the index — instant).
- **PIDs** — comma-separated list, with a `only`/`hide` toggle in the field:
  show only those processes, or drop them and keep everything else.
- **Levels** — E/W/I/D/V + Fatal + Other checkboxes.
- **Time range** — From/To fields (`MM-DD HH:MM:SS[.mmm]`) pre-filled with
  the file's actual span (true min/max — multi-buffer dumps are not
  time-ordered), plus a two-thumb range slider synced both ways.
- **Exclude lines containing** — case-insensitive substrings, whole line.
- **Keep only lines containing** — case-insensitive substrings, whole line.
- Metadata filters are instant (~14 ms on 2M lines); text filters stream the
  file with progress (~5 s on 147 MB).

## Search
- Case-insensitive; optional regex (invalid regex safely matches nothing).
- Yellow match highlighting in the view; match count in the status bar.
- Acts as an additional filter on top of everything else.

## Selection highlighting
- Select text in any log line (drag or double-click a word): all occurrences
  across the view are highlighted cyan, case-insensitive.
- Status bar (right side) shows how many of the currently filtered lines
  contain the selection; recounts when filters change; debounced.
- Ctrl+C copies the selection (within a line).

## Presets
- **Network Debug**, **Crash Debug**, **Video Debug** — one click fills the
  exclude/keep fields per common debugging profiles.
- **Combinable**: several presets can be applied at once. Their keep-lists are
  unioned, so Crash + Network shows both subsystems. Applied presets are
  highlighted in the menu, and clicking one again removes it.
- An exclude from one preset never vetoes another preset's keep-term —
  Network excludes `CCodec`/`Audio`, which Video keeps, so combining them
  keeps both rather than silently dropping Video.
- Editing the Exclude/Keep fields by hand deselects the presets, since the
  text no longer matches what they set.
- **Clear Filters** resets everything to the file's defaults — filters,
  presets, the tag search box and the selection highlight.
- When a filter combination matches nothing, the status bar reports how many
  filters are active rather than showing a bare empty view.

## Export
- Writes the currently filtered lines to a new `.txt`/`.log` file, streaming
  (266 K lines ≈ 0.1 s). Byte-exact copies of the original lines.

## Viewer
- Severity coloring (red/pink/orange/green/blue/gray) + row tint for E/W.
- Monospace, lazy rendering — only visible lines are read from disk (64 KB
  block LRU cache; ~11 ms for 500 random reads).
- White semi-transparent scrollbars, visible on the dark theme.

## Engineering
- All heavy work on background coroutines: UI never freezes; progress +
  Cancel for indexing, text filtering and export.
- 28 unit tests: parser (format edge cases, leap day, tag-with-spaces),
  indexer (CRLF, buffer-spanning lines, offset fidelity), filter engine
  (every filter type, combinations, both tag modes), highlight counter,
  export byte-exactness; plus an opt-in large-file benchmark
  (`SMOKE_FILE` env var).

## Not yet built (planned/deferred)
- Automatic issue detection (network-failure timelines) — spec §14.
- Automatic crash/ANR/tombstone detection.
- Cross-line selection & copy (use Export Filtered for stack traces).
- Saved custom presets.
