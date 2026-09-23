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

Click **Open Log** (top-left) and pick a `.txt` or `.log` logcat dump —
both classic logcat timestamps (`07-12 14:10:14.880`) and cached logs
with year-prefixed timestamps (`2026-07-14 14:27:36.530`) are
supported. Any size works — a 150 MB file indexes in under a second, gigabyte files in a few
seconds with a progress bar. While it loads you'll see
`Indexing: X / Y MB` at the bottom; you can Cancel.

Captures made with `adb logcat > file.txt` in Windows PowerShell work too:
PowerShell saves them as UTF-16, which Sherlog detects and reads as-is.
Exporting such a file writes UTF-8.

When it finishes, the bottom bar shows `filtered / total lines` on the left
and the file's **Errors / Warnings / Unique tags** in the middle, and the
time-range fields are pre-filled with the first and last timestamp in the file.

## Several files at once (tabs)

Every opened file gets its own tab across the top, as in Sublime Text:

- **Open Log** lets you pick several files at once (Ctrl/Shift-click in the
  dialog); **+** at the end of the tab strip does the same.
- **Open Folder** opens every `.txt` and `.log` file directly inside the
  chosen folder (subfolders are skipped), in name order.
- Tabs shrink as more files open and the strip scrolls once they reach their
  smallest readable width; switching tabs always scrolls the active one into
  view, and **+** never scrolls away.
- Drag a tab sideways to reorder it; the file you are looking at stays on
  screen while the strip rearranges.
- Click a tab to show it, **×** to close it. Hover a tab for the file's full
  path. Opening a file that's already open just shows its tab.

Each tab keeps its own filters, search, highlight and scroll position. To
use the same filters everywhere, set them up in one tab and click **Apply
filters to all tabs** (next to Clear Filters). It copies the tags, PIDs,
levels, time range, exclude/keep text, presets and search to every other tab:

- Tags apply by name — a tag another file doesn't have simply matches nothing.
- The time range carries over only if you narrowed it. Left at the file's full
  span, each tab keeps its own span, since different logs cover different times.

Afterwards each tab shows its line count, so you can see at a glance which
files have hits. The button waits while files are still loading.

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

**PID** — comma-separated process IDs, e.g. `1913, 6432`. The `only`/`hide`
toggle on the right of the field switches between showing only those processes
and dropping them; in `hide` mode it turns red so an active hide is obvious.

**Time range** — the From/To fields come pre-filled with the file's full
span. Either edit them (`MM-DD HH:MM:SS`, optional `.mmm` — note logcat uses
the 24-hour clock, so 1 PM is `13:00`) or drag the two-thumb slider below;
slider and fields stay in sync both ways. For precise windows on multi-day
logs, typing the times is more accurate than dragging.

**Exclude lines containing** — comma-separated substrings; any line
containing one of them (anywhere in the line, case-insensitive) is removed.

**Keep only lines containing** — comma-separated substrings; when non-empty,
a line must contain at least one to stay.

## Search

Case-insensitive; tick **Regex** for patterns. The icon on the right toggles
two modes:

- **Filter** (default) — narrows the view to matching lines.
- **Find** — keeps every line and highlights matches in place; the ▲ ▼ arrows
  step through them, like Ctrl+F in an editor.

Typing takes over the highlight from a double-click selection; clear the box
to get it back.

**Searching for what you selected.** Select any text in a line and press
`Ctrl`+`F`: it goes straight into the search box, no copying needed. The mode
stays as it is — Filter narrows the view to it, Find highlights it in place.
Then `Enter` jumps to the nearest match from where you are and `Shift`+`Enter`
goes back, the same as the ▲ ▼ arrows. On a very large file the first `Enter`
waits for the match count to finish rather than doing nothing.

## Searching every open tab (`Ctrl`+`Shift`+`F`)

Press `Ctrl`+`Shift`+`F` to open the results panel at the bottom. It has its
own search box — pre-filled from whatever you had selected — so searching
across files never touches any tab's filters or its own search box. Type and
press `Enter`.

Results are grouped by file, `alpha.txt (3,749)`, newest group appearing as
each file finishes. Click a group's name to fold it away; click any result to
jump to that line in its own tab. Drag the bar above the panel to resize it,
and press `Esc` (or ✕) to close it.

Each tab is searched over **the lines it currently shows**, so its filters
still apply and every result can be jumped to. Long result lists are trimmed
to 500 lines per file — the count beside the file name is always the true
total. Regex works here too, though a regex result jumps without highlighting.

## Highlight occurrences of a selection

Select any text inside a log line — drag-select a phrase or **double-click a
word** (a tag, an exception name, a serial number). Every occurrence across
the view lights up cyan, and the bottom-right corner shows
`N lines contain "…"`. Click anywhere to clear it. Ctrl+C copies the
selection.

## Selecting and copying several lines

Drag from one line onto another, or click a line and **Shift+click**
another, to select every line in between. Keep dragging past the top or
bottom edge and the view scrolls. Selected lines are tinted and the status
bar shows `N lines selected`.

Copy them with **Ctrl+C**, the status bar's **Copy**, or **right-click →
Copy N selected lines**. Lines are copied exactly as they are in the file,
one per line (up to 100,000 lines at a time; use Export Filtered for more).
A plain click, **Esc**, the status bar's ✕ or any filter change clears the
selection.

Selecting across lines selects whole lines, and it doesn't highlight
occurrences the way a selection inside one line does. A drag that stays
within a single line still works as before.

**Stepping through matches.** ▲ ▼ next to the count (or `F3` /
`Shift`+`F3`) move between highlighted lines — this works for Find-mode
searches too — and always start from where you are, never back at the top:

- after a double-click, from the occurrence you clicked (in Find mode, a
  plain click on a found line does the same);
- while the current (amber) match is on screen, from that one;
- once you've scrolled away, from the lines on screen: ▼ takes the first
  match on screen (or the next one below it), ▲ the last one (or the next one
  above).

The view stays still while the next match is already visible, and only
scrolls when it isn't. Past the last match, ▼ wraps to the first.

## Presets (top bar)

- **Network Debug** — hides `adbd/CCodec/Audio/Surface/OpenGL/BufferQueue`,
  keeps `OkHttp/Retrofit/DnsResolver/NetworkMonitor/ConnectivityService`.
- **Crash Debug** — keeps only `FATAL EXCEPTION / AndroidRuntime / Exception /
  Caused by / StackTrace` lines.
- **Video Debug** — keeps only `CCodec / MediaCodec / Camera / Audio` lines.

Presets fill the Exclude/Keep fields — you can tweak them afterwards, though
editing either field by hand deselects the preset that wrote it.

**Presets combine.** Pick more than one and their keep-lists merge, so
Crash + Network shows crashes *and* network traffic. Applied presets stay
highlighted in the menu; click one again to remove it. The menu stays open
while you pick, so click elsewhere or press Esc to close it.

Presets stack on top of your other filters rather than replacing them — a
preset chosen while a tag is checked shows only that tag's matching lines. If
that leaves nothing, the status bar says `0 lines — N filters active`.

**Clear Filters** resets everything (time range resets to the file's full
span), including presets, the tag search box and the selection highlight.

## Exporting

**Export Filtered** (top-right) writes exactly the lines you currently see to
a new `.txt`/`.log` file (default `cleaned_logcat.txt`). A 266K-line export
takes well under a second.

## Keyboard shortcuts

| Key | Action |
|-----|--------|
| `Ctrl`+`F` | Search for the selected text, or just jump to the search box |
| `Ctrl`+`Shift`+`F` | Search every open tab (results panel) |
| `Enter` / `Shift`+`Enter` | In the search box: next / previous match |
| `F3` / `Shift`+`F3` | Next / previous match |
| `Esc` | Clear the line selection, then the search, then the highlight |
| `Ctrl`+`O` | Open Log (adds tabs) |
| `Ctrl`+`E` | Export Filtered |
| `Ctrl`+`Tab` / `Ctrl`+`Shift`+`Tab` | Next / previous tab (also `Ctrl`+`PgDn` / `PgUp`) |
| `Ctrl`+`W` | Close the tab |
| `Ctrl`+`C` | Copy the selected lines, or the text selected within a line |

## Tips

- The status bar always reads `filtered / total lines`; watch it to see what
  a filter change did.
- Text-based filters (search, exclude/keep) rescan the file, so on a very
  large file expect a few seconds with a progress bar; tag/PID/level/time
  filters are instant.
- Editing logs happens outside the app: select→copy into your editor, or
  Export Filtered and open the result. The log view itself is read-only.
