# SongListener

A proof of concept for recognising which song the band is playing from the music alone. It learns
from real services and suggests songs. It runs beside ChurchPresenter and the STT server and
changes neither of them.

## How it works
- **ChurchPresenter** (`ws://host:8765/ws`, `live_state_changed`) tells it which song is on screen.
- **STT** (Socket.IO `join_audio_stream` → `audio_chunk` 16 kHz PCM, `audio_level` → `music_prob`)
  gives it the room audio and whether music is playing.
- **Learn:** while a song is on screen and music plays, it records chroma features (12 pitch-class
  energies, 2 per second). If there are ≥30 s of music when the song leaves the screen, it saves a
  take. It keeps the newest 5 takes per song under `~/.songlistener/takes/`.
- **Intro look-back:** while nothing is on screen, it holds up to 90 s of music. A break of
  more than 20 s without music clears it. When a song goes live, that music is saved at the
  start of its take as the intro, so next time the intro alone can bring up the suggestion before
  anything is said or shown. Only music played while the song was on screen counts toward the
  30 s minimum.
- **Dips:** STT's music reading often dips below 0.5 for a second between verses. A dip of up to
  2 s right after music is treated as music, and only a longer gap (`music_break` in the log)
  restarts matching.
- **Suggest:** while nothing is on screen and music plays, it matches the last 12 s against every
  take every 2 s. It tries all 12 keys and tempos from ×0.92 to ×1.08. It suggests a song when
  score ≥ 0.80, it leads the next song by ≥ 0.05, and it wins twice in a row. A song taken off
  screen is not suggested back for 2 minutes.
- **Section:** a match also reports which slide was on screen at that point in the learned take,
  shown as "Band is at: section 3" or "intro". Only parts with different music can be told apart:
  when every verse of a hymn has the same tune, it can tell intro from verse (and from a chorus
  with its own tune) but not verse 1 from verse 3.
- **Accuracy:** while a song that already has takes is on screen, it scores what it would have
  predicted, so one service gives you a hit rate.

All tuning values are in `src/main/kotlin/songlistener/core/Config.kt`.

## Run
```bash
./gradlew createDistributable                              # builds SongListener.app
open build/compose/binaries/main/app/SongListener.app      # or double-click it / copy to Applications
./gradlew test                                             # core tests on synthesised chord progressions
```
Re-run `createDistributable` after pulling changes. `./gradlew run` works for development too.

To enter an address, click the box, press ⌘A, then type it with `http://` and the port, e.g.
`http://192.168.1.50:8765` and `http://192.168.1.60:80`. **Connect** saves both addresses.

Java on macOS can lose keyboard focus after you switch to another app and click back.
`ui/FocusWedgeFix.kt` repairs that on your first click. If typing ever goes dead anyway, click
once more inside the window.

## Web UI
While the app runs it also serves a read-only page with the same three columns (Hearing,
Accuracy, Learned songs) at `http://<this machine>:8780`, refreshed every second. It has no
buttons, so anyone on the network can watch but not change anything. If the port is taken the
web page is skipped and the app starts as usual.

## Running it on a service
1. **Before:**
   - Start ChurchPresenter.
   - Start the STT server and **start transcription**. STT only sends audio and `music_prob`
     while it is transcribing.
   - Open SongListener.app and press **Connect**. Both dots turn green.
   - Keep the Mac from sleeping.
2. **Soundcheck:** the Music meter should turn green while the band plays. If STT hears only a
   speech mic, music will barely register, so a feed from the mixing desk is best.
3. **During:** use ChurchPresenter as normal. Each song on screen records its music; a take is kept
   once "Recording take" passes 30 s.
4. **After:** close the window normally, which saves the take in progress. Takes are in
   `~/.songlistener/takes/`.

The first service only learns. From the second, the Accuracy panel shows how often a learned song
on screen is recognised, and music played with nothing on screen produces suggestions. Accuracy
counts reset when the app restarts, but every match run is kept in the review log (below).

Once connected, STT keeps streaming audio until the server restarts. It costs little, because
unread chunks are dropped.

## Hearing, wrong slides and "Show it"
SongListener **reports only**. Nothing goes on screen without a click.
- **Two listeners:**
  - **quick** (12 s window, score ≥ 0.80): an early "Sounds like…" guess;
  - **steady** (36 s window, score ≥ 0.64): a confirmed **Suggested**, the accuracy count, and
    the wrong-slide check.

  The settings were chosen by a replay sweep on 37 recordings (2026-10-01).
- **Wrong slide:** while a learned song is on screen, if another song beats it by 0.06 or more on
  two steady runs in a row, a red banner appears: "Music sounds like #J — screen shows #K". In
  replay it caught 5 of 6 deliberately wrong slides, with 1 false alarm in 6. If the song on screen
  hasn't been learned, the panel says it can't be checked.
- **Show it** sends ChurchPresenter `POST /api/project` with the song and its songbook (the one it
  was last shown from, or each edition when unknown).
  - ChurchPresenter asks its operator the first time. Choose "always allow" there to skip the
    prompt from then on.
  - SongListener keeps one fixed device id in `config.json` so that choice sticks.
- **What replay says today:** the early guess is right before the slide in 0/6 tests, and the
  confirmed suggestion is right in 4/6, with 1 wrong, all after the slide. The operator usually
  puts a song up within 8 s of the music, so early guesses only matter for the few late slides.
  Wrong-slide detection is the reliable part.

## Song recordings
For every song whose take is saved, SongListener keeps a recording of **that song only**, not the
service. It goes in `~/.songlistener/clips/<date_time>_<number>.wav` (16 kHz mono, about 2 MB a
minute), with a `.json` of labels beside it.
- **Start:** 3 s before the music that became its intro, or 3 s before it went live if there was
  no intro.
- **End:** 3 s after it left the screen. If the slide was left up, it ends 3 s after the last
  music once there have been 20 s without music. Recordings are capped at 10 minutes.
- **Labels:** when it went live, section changes, when it left the screen, and STT's music on/off
  timeline. The label file also links the recording to the song's `.take`.
- **Marked imported or live:** `origin` is `live` (recorded by the app) or `import` (cut from an
  STT backup), and an import also records `importSource` (`play_log`, `songlistener_log` or
  `churchpresenter_history`). Recordings saved before this existed are all imports. The Learned
  songs panel counts them separately, and `./gradlew replay -Porigin=live` tests on one kind only.
- **Never deleted automatically.** **Forget** (two clicks) deletes that song's takes *and*
  recordings, because a song is usually forgotten for being mislabelled.
- The Learned songs panel shows the count and the size on disk.

These are recordings of the congregation singing. They stay on this machine and are never
committed to any repo.

## Lyrics
When a song goes live, SongListener fetches its words from ChurchPresenter's Companion API. It
finds the song in the song list and fetches it by id: asking by title matches every unnumbered
song first. It keeps a copy in `~/.songlistener/lyrics/<song>/<songbook>/<time>_<hash>.json`.
- A new file is written **only when the words changed**, so each folder is that song's edit history.
- Each recording names the version it was sung from (`lyricsVersion`).
- Only words are kept: chords, slide breaks and background directives are stripped. Sections split
  by ChurchPresenter's own heading rule (`[…]` = verse/other, `{…}` = chorus).
- The live feed carries no songbook. When the same number and title exist in two songbooks with
  different words (Песнь Возрождения 2001 and 3300, for instance), the copy is marked "songbook
  uncertain". ChurchPresenter's history log (see the prompt in `docs/`) will remove that doubt.

## Importing past services
```bash
# one service, or every <session>.wav in a folder
./gradlew importService -Pwav=<file or folder> -Pdb=<file or folder> \
    [-PcpLogs=<ChurchPresenter bible-stt-logs folder>] [-PplayLog=<play_log.json>] \
    [-PseedTakes=true] [-PdryRun=true]
```
This cuts song recordings out of STT's whole-service backups (`_AUTOMATIC_BACKUP/<year>/<month>/`,
subfolders included, so `-Pwav=…/_AUTOMATIC_BACKUP/2026` covers the whole year).

**Only real services are imported.** Each session is classified with STT's own rule, ported from
`stt/session_cleanup.py`:
- **service:** starts within 2 h before to 2.5 h after Sun 10:00, Sun 18:00 or Wed 19:00, and
  runs 90+ minutes;
- **fragment:** in a window but shorter;
- **unusual:** off schedule with 300+ transcript lines, e.g. a wedding;
- **test:** everything else, including generated demo sessions.

Only `service` is imported. Use `-PincludeUnusual=true` or `-PincludeAll=true` to widen that. A
recording is paired with its database by name, or the nearest within 30 minutes. A `.wav` much
shorter than its session is flagged as incomplete.

**Lyrics:** `-PsongCache=<song_cache.json>` stamps each imported recording with the words from
ChurchPresenter's cache, in the songbook the on-screen record names. `song_cache.json` is
ChurchPresenter's own parsed copy of the song folder. The cache has *today's* words, and the import
says when a song was edited after that service. Songs in two editions with different words and no
known songbook get no lyrics rather than possibly the wrong ones.
A service is one STT session: `<session>.wav`, its `<session>.db` (found next to the `.wav` or in
the `-Pdb` folder; ChurchPresenter's `bible-stt-logs` keeps copies), and a record of what was on
screen. The first one that exists is used, or force one with `-Psource=cp|songlistener|playlog`:
1. **ChurchPresenter's history**, `live-content-<session>.jsonl` in `-PcpLogs`. It has go-lives,
   sections, leaving the screen and the songbook. See `docs/churchpresenter-live-history-prompt.md`.
2. **SongListener's own log** for that day.
3. **`play_log.json`**, which has go-lives only. Each song is ended where its music ends.

How it works:
- **Music on/off:** STT's own reading when SongListener's log is the source. Otherwise it is worked
  out from the audio by how steady the harmony is frame to frame: sustained chords versus speech.
  On 2026-09-30 that marked 96% of song seconds and 2% of the (equally loud) sermon. Recordings cut
  that way matched those cut with STT's reading to within 1–2 s. `-Pmusic=audio` forces it.
- **Clock:** the backup's clock drifts, so each moment is mapped through the session database, then
  fine-tuned per song by finding its take in the audio.
- **Clock skew:** when both ChurchPresenter's history and SongListener's log exist, the difference
  between the two machines' clocks is measured, and corrected if it is over 2 s.
- **`-PseedTakes=true`:** also learns a take from each new recording, so the live app recognises
  those songs straight away (restart it afterwards). Songs already learned live at that service are
  skipped.
- `-PdryRun=true` prints what it would do and saves nothing.
- Songs that already have a recording from that service are skipped.
- The database is read from a temporary copy, timing only.

## Replay
```bash
./gradlew replay                                   # current settings
./gradlew replay -PsuggestScore=0.75 -PsuggestMargin=0.08
```
Replay is leave-one-out. Each recording is tested against takes learned from all the *other*
recordings, so a song can only be recognised from a different performance of it. Each one is
played twice:
1. **Nothing on screen:** was it suggested, was it right, and how many seconds before it went
   live (`lead`)?
2. **As it really was on screen:** how often it ranked first (`top1`), and how often the section
   was right (`sect`).

Per recording it prints the best right-song and best wrong-song score, which is the gap the
thresholds have to sit in. It ends with a summary and writes `reports/replay-<time>.csv`. A song
with only one recording is listed as not testable.

You can override `suggestScore`, `suggestMargin`, `suggestStreak`, `queryFrames` (2 per second),
`steadyFrames`, `steadyScore`, `steadyMargin`, `wrongSlideMargin`, `musicDipToleranceSeconds`,
`tempoStretches` (comma-separated), `transpose`, `feature` (`chroma` | `melody`) and `origin`.

Replay also measures:
- the early guess as well as the confirmed suggestion;
- **late slides** (music 20 s or more before the slide): caught in time?
- **wrong slides:** each recording is replayed with another song on screen. Was the warning
  raised, and was it raised falsely when the right song was on screen?

## Service report
**Report** (next to Open logs), or `./gradlew report`, turns each day's log into
`~/.songlistener/reports/<date>.html` and opens the newest. It contains:
- a timeline of songs on screen, music, and every match score against the 0.80 line;
- per song: time on screen, music heard, the take, hits and misses, whether the section was right,
  and the best scores for the right song and for any wrong song (the gap that matters);
- each suggestion, and whether that song went on screen next;
- **Late slides:** each song whose slide went up 20 s or more after its music started. It shows how
  late, whether the early guess or the suggestion named it first (and how early), and any Show-it
  click. The app writes a `late_slide` line the moment it happens; for older logs the section is
  worked out from when the music started.
- **Wrong slides:** each warning, with what was on screen, what the music sounded like and both
  scores, then what came of it: Show it (and ChurchPresenter's answer), dismissed, slide changed,
  or the music matched again.
- a music-detection summary: music heard, starts, short dips, real breaks.

The timeline marks the music before a late slide in orange, wrong-slide warnings with red markers,
and early guesses as yellow dots. `./gradlew report -Plogs=<folder> -Pout=<folder>` reports on
another set of logs.

## Review log
Everything it does is logged to `~/.songlistener/logs/<date>.jsonl`, one JSON object per line,
each stamped with local time and a `type`. The **Open logs** button opens the folder.

| type | fields |
|---|---|
| `app_started`, `app_stopped`, `connect` | `detail` holds the addresses on `connect` |
| `churchpresenter_connected` / `_disconnected`, `stt_connected` / `_disconnected` | |
| `song_live`, `section_changed` | `song`, `key`, `section` |
| `song_off_screen` | the song left the screen (the screen may still show other content) |
| `music_started`, `music_stopped` | `musicProb` at the moment STT's reading crossed 0.5 (raw, dips included) |
| `music_break` | music gone longer than the 2 s dip tolerance; matching restarts |
| `lookback_cleared` | `heldSeconds` of music thrown away after a break of more than 20 s |
| `take_started` | `song`, `introSeconds` taken from the look-back |
| `take_saved` | `song`, `introSeconds`, `songSeconds`, `takesKept` |
| `take_discarded` | the same, when there was under 30 s of music with the song on screen |
| `late_slide` | `song`, `key`, `lateSeconds`, `hintLeadSeconds` / `suggestionLeadSeconds` (if named first), `caught` |
| `wrong_slide_warning` | `song`/`key` on screen, `shownScore`, `heard`/`heardKey`, `heardScore` |
| `wrong_slide_cleared`, `wrong_slide_dismissed` | the warning ended (music matched again, or slide changed), or the operator dismissed it |
| `show_requested`, `show_result` | a Show-it click, and ChurchPresenter's answer (`shown`, `declined`, `failed: …`) |
| `match` | `window` (`quick` or `steady`), `live` (song on screen or null), `liveSection`, `top` (3 × song, score, transpose, tempo, section), `margin`, `confident`, `decision` |
| `song_forgotten` | `detail` is the song key (its takes and recordings are deleted) |
| `lookback_started` | music began while nothing was on screen: a possible intro |
| `clip_saved`, `clip_dropped` | a song recording kept (file, length, why it ended) or not kept (why) |

`decision` on a match is one of:
- `suggested`, `streak 1/2`, `below_threshold`, `suppressed` (the song just taken off screen), or
  `already_suggested`, while nothing is on screen;
- `accuracy_hit` or `accuracy_miss` while a learned song is on screen;
- `live_unlearned` while an unlearned song is on screen.

Quick looks:
```bash
grep -h '"take_' ~/.songlistener/logs/*.jsonl                                # what was learned
grep -h '"suggested"' ~/.songlistener/logs/*.jsonl                           # every suggestion
grep -hc '"accuracy_hit"' ~/.songlistener/logs/*.jsonl; grep -hc '"accuracy_miss"' ~/.songlistener/logs/*.jsonl
```
A match line is written every 2 s while music plays, so a service is a few thousand lines.
Logs hold song names, timings and scores only: no audio, and no transcript.

Takes are derived from service audio. They are git-ignored (`*.take`) and never committed.

## Known limits
- The song key is `number|title`, because the live-state event carries no songbook.
- Songs built on the same chord progression in the same order will score close together. The
  margin rule is what guards against that; watch the Accuracy panel.
- If one song's outro runs straight into the next song going live with no break, the outro is
  saved as the next song's intro.
- Matching is an exhaustive search. That is fine for tens of songs; hundreds will need an index.
