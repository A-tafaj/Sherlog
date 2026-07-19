# Sherlog

A desktop **logcat viewer for large log files** — open an Android logcat dump
far too big for a text editor, then filter it down by tag, PID, level, time and
text instead of grinding through `findstr` and regex passes by hand.
Kotlin + Compose Desktop.

![Sherlog filtering a 1.5-million-line logcat down to 36,802 lines](docs/images/screenshot.png)

A 1,555,005-line dump narrowed to **36,802 lines** by one tag and a time range,
with every occurrence of the selected text highlighted and counted
(*74 lines contain "DISCONNECTED"*, bottom right) so you can step through them.

Docs: [User Guide](docs/USER_GUIDE.md) · [Features](docs/FEATURES.md) ·
[Why this tool](docs/WHY_THIS_TOOL.md).

## Install

Grab the installer for your platform from the
[latest release](https://github.com/A-tafaj/Sherlog/releases/latest):

| Platform | File |
|----------|------|
| Windows | `Sherlog-windows-x64.msi` |
| macOS (Apple Silicon) | `Sherlog-macos-arm64.dmg` |
| macOS (Intel) | `Sherlog-macos-x64.dmg` |
| Linux (Debian/Ubuntu) | `Sherlog-linux-x64.deb` |

**No Java required** — every installer bundles its own runtime.

On Linux, install with `sudo apt install ./Sherlog-linux-x64.deb` so
dependencies resolve.

### "Windows protected your PC" / "Sherlog can't be opened"

These builds aren't code-signed — certificates are a recurring cost this
project doesn't carry — so both operating systems will warn you the first
time. The app is unaffected; you're being told nobody paid to vouch for it.

- **Windows** — on the SmartScreen dialog, click **More info**, then
  **Run anyway**.
- **macOS** — Gatekeeper refuses a double-click. Right-click (or Ctrl-click)
  Sherlog in Applications, choose **Open**, then **Open** again in the
  dialog. Once only. If macOS instead claims the app "is damaged", clear the
  quarantine flag:

  ```
  xattr -cr /Applications/Sherlog.app
  ```

If you'd rather not trust a stranger's binary at all, building from source
takes about a minute — see [Run](#run) below.

## Features (MVP)

- Open `.txt` / `.log` logcat dumps far larger than an editor will take —
  1.5M-line files index in under a second. The file is **indexed, never loaded
  into memory**; line text is read from disk on demand.
- Parses threadtime format: timestamp, PID, TID, level, tag.
- Dashboard: total lines, errors, warnings, unique tags, filtered count.
- Top-tags list with counts, sortable by count or A–Z, searchable, with
  checkbox include-filtering.
- Filters: tags, PIDs, time range (`MM-DD HH:MM:SS`), levels,
  exclude-substrings, keep-substrings. All combinable. Tags and PIDs each
  toggle between **show-only** and **hide**.
- Search: case-insensitive, optional regex, highlighted matches, match count.
- Debug presets: Network / Crash / Video — **combinable**, so Crash + Network
  shows both at once.
- Export the filtered view to a new `.txt`/`.log` file (streaming).
- All heavy work runs on background coroutines with progress + cancel.

## Roadmap

Planned, roughly in priority order. Contributions welcome — open an issue
before starting anything large.

- [ ] **Multiple files open at once** — tabs or split panes, so several dumps can be compared
      without reopening. Each tab keeps its own index and filter state.
- [ ] **Save the current filter set as a preset** — name and store whatever is
      configured (tags, PIDs, levels, time range, exclude/keep text) alongside
      the built-in Network / Crash / Video presets, and edit or delete them
      later.
- [ ] **Persist presets to disk** — so custom presets survive a restart and can
      be shared with a teammate as a file.
- [ ] **Session restore** — reopen the last file with its filters intact.

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

Requires JDK 17+ — nothing else. Works on any platform Compose Desktop
supports.

```
./gradlew run          # macOS / Linux
.\gradlew.bat run      # Windows
```

## Test

```
./gradlew test         # macOS / Linux
.\gradlew.bat test     # Windows
```

## Package an installer

```
./gradlew packageDistributionForCurrentOS
```

Builds whichever format the current machine can produce — `.msi` on Windows,
`.dmg` on macOS, `.deb` on Linux. jpackage cannot cross-compile, so each
release installer is built on a runner of that OS; see
[`.github/workflows/release.yml`](.github/workflows/release.yml).

## License

Released under the [MIT License](LICENSE) — free to use, modify, and
distribute. Contributions welcome.
