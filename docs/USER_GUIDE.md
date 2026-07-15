# Sherlog — User Guide

## Starting the app

```
cd C:\Users\AliTafaj\AndroidStudioProjects\sherlog
.\gradlew.bat run
```

Or build a Windows installer once and use it from the Start menu:

```
.\gradlew.bat packageMsi     # output under build\compose\binaries\
```

## Opening a log

Click **Open Log** (top-left) and pick a `.txt` or `.log` logcat dump. Any
size works — a 150 MB file indexes in under a second, gigabyte files in a few
seconds with a progress bar. While it loads you'll see
`Indexing: X / Y MB` at the bottom; you can Cancel.

When it finishes, the dashboard row shows **Total lines / Errors / Warnings /
Unique tags / Filtered**, and the time-range fields are pre-filled with the
first and last timestamp in the file.

## Reading the log view

- Lines are colored by severity: red = Error, pink = Fatal, orange = Warning,
  green = Info, blue = Debug, gray = Verbose. Error/warning lines also get a
  faint background tint.
- Scroll with the mouse wheel; the scrollbar on the right is draggable.

## Filtering (left panel — everything applies automatically as you change it)

**Tags**
- The list shows every tag with its line count. Type in *Search tags* to
  narrow the list; click the sort label to switch between count and A–Z.
- Check tags, then pick the mode with the two chips:
  - **Show only checked** — the view keeps only those tags.
  - **Hide checked** — those tags disappear (perfect for muting `adbd`,
    `CCodec`, `BufferQueue` noise).
- Flipping the mode keeps your checks, so it doubles as an "invert" switch.
- *Clear (n)* unchecks everything.

**Levels** — checkboxes for E/W/I/D/V, plus Fatal and *Other* (lines that
aren't standard logcat format, e.g. stack-trace dumps).

**PID** — comma-separated process IDs, e.g. `1913, 6432`.

**Time range** — the From/To fields come pre-filled with the file's full
span. Either edit them (`MM-DD HH:MM:SS`, optional `.mmm`) or drag the
two-thumb slider below; slider and fields stay in sync both ways.

**Exclude lines containing** — comma-separated substrings; any line
containing one of them (anywhere in the line, case-insensitive) is removed.

**Keep only lines containing** — comma-separated substrings; when non-empty,
a line must contain at least one to stay.

## Search

The search box above the log view is case-insensitive; tick **Regex** for
regular expressions. Matches are highlighted yellow and the status bar shows
the match count. Search combines with all other filters.

## Highlight occurrences of a selection

Select any text inside a log line — drag-select a phrase or **double-click a
word** (a tag, an exception name, a serial number). Every occurrence across
the view lights up cyan, and the bottom-right corner shows
`N lines contain "…"`. Click anywhere to clear it. Ctrl+C copies the
selection.

## Presets (top bar)

- **Network Debug** — hides `adbd/CCodec/Audio/Surface/OpenGL/BufferQueue`,
  keeps `OkHttp/Retrofit/DnsResolver/NetworkMonitor/ConnectivityService`.
- **Crash Debug** — keeps only `FATAL EXCEPTION / AndroidRuntime / Exception /
  Caused by / StackTrace` lines.
- **Video Debug** — keeps only `CCodec / MediaCodec / Camera / Audio` lines.

Presets fill the Exclude/Keep fields — you can tweak them afterwards.
**Clear Filters** resets everything (time range resets to the file's full span).

## Exporting

**Export Filtered** (top-right) writes exactly the lines you currently see to
a new `.txt`/`.log` file (default `cleaned_logcat.txt`). A 266K-line export
takes well under a second.

## Tips

- The status bar always reads `filtered / total lines`; watch it to see what
  a filter change did.
- Text-based filters (search, exclude/keep) rescan the file, so on a 1 GB
  file expect a few seconds with a progress bar; tag/PID/level/time filters
  are instant.
- Editing logs happens outside the app: select→copy into your editor, or
  Export Filtered and open the result. The log view itself is read-only.
