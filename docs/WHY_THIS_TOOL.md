# Why This Tool — Motivation & Benefits

## The problem it replaces

Debugging Android device issues means digging through logcat dumps that
are hundreds of megabytes — `daily-log_*.txt`, `loop-test logs`, customer
device pulls. The old workflow was:

- Chains of PowerShell `findstr` / `Select-String` commands, re-typed or
  re-edited for every question you ask of the same file.
- Text editors that choke or freeze on 500 MB+ files (or refuse to open them).
- No overview: you can't see *what's in* a file (which tags dominate, how many
  errors, what time span) before you start grepping blind.
- Repeating the same cleanup recipe (strip `adbd`, `CCodec`, `BufferQueue`…)
  by hand every single time.
- Losing context: a grep shows matching lines only — the stack trace that
  belongs to a `FATAL EXCEPTION` line is in a different grep.

## What this tool gives you instead

**Speed on real file sizes.** A 147 MB / 2-million-line file opens in ~0.6 s.
Tag/PID/level/time filter changes apply in ~14 ms — you can iterate on a
question as fast as you can think of it, instead of re-running a findstr that
rescans the whole file each time.

**An overview before you dig.** The dashboard and the tag list with counts
tell you immediately what the file contains — 12K `OkHttp` errors
concentrated in one hour is a finding you get in the first ten seconds.

**Interactive narrowing, not one-shot queries.** Filters compose: "errors
and warnings, from PID 1913, between 14:00 and 14:30, hiding codec noise,
containing 'timeout'" is five clicks, all reversible, with the line count
updating at every step. The time-range slider makes "the problem happened
around 14:10" a drag instead of a timestamp-typing exercise.

**Team recipes become one click.** The Network/Crash/Video presets encode
the exclude/keep lists we previously kept in our heads (or in old shell
history). New teammates get the same cleanup without knowing the recipe.

**Context is preserved.** Stack traces and marker lines inherit the timestamp
of the log line before them, so a time filter around a crash keeps the whole
trace. Level "Other" keeps them togglable instead of silently lost — a
classic grep failure mode.

**Cross-referencing built in.** Select any string — a tag, an exception
class, a device serial — and every line containing it lights up, with a count
of how many filtered lines mention it. That's the "how widespread is this?"
question answered without leaving the view.

**A clean artifact at the end.** Export Filtered turns a 1.5M-line dump into
a 20K-line file you can attach to a ticket, share with a colleague, or open
in a normal editor — the cleanup steps are reproducible instead of being a
one-off pipeline.

**It can't freeze on you.** Indexing, text filtering and export all run in
the background with progress bars and a Cancel button. The UI stays
responsive no matter the file size — the whole design (streaming index,
on-demand line reads) exists so that "just open the 1 GB file" is a
reasonable thing to do.

## Concrete workflows it accelerates

- **API/network failures** (cert rotation, TLS, OkHttp timeouts):
  Network preset → search `timeout` → select the failing host to see every
  mention → export the evidence.
- **Crash triage**: Crash preset → the FATAL line and its full trace are
  together, timestamped → time-slider to the minutes around it to see what
  led up to it.
- **Customer device logs**: open whatever QA/support sends, regardless of
  size, get the dashboard overview, and answer "was the device even online at
  the reported time?" from the time range + ConnectivityService lines.
- **Comparing runs**: the same preset applied to two `daily-log` files
  gives directly comparable filtered views.

## The cost it saves

Every log question used to cost a hand-written command plus a full-file scan
(minutes on big files, times every iteration). Now the scan happens once at
open; nearly every question after that is answered in milliseconds. Over the
many log-debugging sessions per week this team does, that is hours per week —
and the bigger win is qualitative: cheap iteration means you actually *ask*
the follow-up questions instead of settling for the first grep that
half-answers.
