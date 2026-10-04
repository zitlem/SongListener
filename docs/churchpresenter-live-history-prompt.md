# Prompt: ChurchPresenter on-screen history log

Given to the agent working in the ChurchPresenter repo. SongListener's `importService` reads the
`live-content-<session>.jsonl` files this produces.

**Add an on-screen history log to ChurchPresenter, keyed by the STT session id, with the same
retention as the Bible training logs.**

**Why:** an external tool (SongListener) cuts labelled song recordings out of STT's service
backups. It needs to know what was on screen and when, for every service, joined exactly to
the STT session. `play_log.json` isn't enough: it has go-lives only, no off-screen times or
sections, no session id, and it misses some go-live paths. (2026-09-30's four songs never
reached it.)

**Follow `utils/TrainingDataLogger.kt` exactly:**
- the same folder, `~/.churchpresenter/bible-stt-logs/`;
- session-keyed file names (the STT session id, else the process-start `runStamp`);
- the one-time session header line;
- best-effort writes (`runCatching`, never thrown into the UI path, `synchronized` appends);
- **the same 30-day sweep:** add the new prefix to `TrainingDataLogger.cleanupOldLogsOnce()` so
  one policy covers the folder.

Don't invent a retention setting.

**1. New `utils/LiveHistoryLogger.kt`** (an `object`) writing `live-content-<suffix>.jsonl`. It
writes one JSON line per on-screen change, **de-duplicated**: content setters fire on every call
even when nothing changed (see `CompanionServer.kt:927`), so only write when the logged fields
differ from the previous line.
- **Every line:** `ts_ms` (wall clock), `sessionId`, `contentType` (the `Presenting` enum name;
  `NONE` when cleared or blank), and `source` when known (`manual` / `schedule` / `remote` /
  `auto`).
- **LYRICS:** `songId` (`songbook::number`, as `SongItem.songId`), `songbook`, `songNumber`,
  `songTitle`, `sectionIndex`, `sectionType`, `lineIndex`.
- **BIBLE:** the canonical verse code (as `liveVerseCode` in `LiveStateBroadcastWiring.kt`) and
  the displayed reference.
- **PRESENTATION:** file name and slide index.
- **MEDIA / PICTURES:** file name.
- **Anything else:** `contentType` only.

Log identifiers only, never lyric, verse or slide text.

**2. Hook point.**
- `PresenterManager.onLiveStateChanged` (`viewmodel/PresenterManager.kt:71-74`) sees every
  on-screen change, but it has a single listener, already set in
  `LiveStateBroadcastWiring.kt:33-78`.
- Log from that listener, next to `companionServer.updateLiveState(...)`, or turn the callback
  into a listener list. Pick the smaller change and say why.
- It must log even when the Companion server is off.

**3. Full song identity.**
- `LyricSection` carries no songbook, and `PresenterManager` doesn't know it.
- The go-live paths that have the full item (`songId`/`songbook`) are:
  - Songs tab: `SongsTab.kt:217-233` → `onSongWentLive` → `MainTabArea.kt:248`;
  - schedule: `ScheduleRowHandlers.kt:20-37`;
  - remote: `RemoteApply.kt:443-470`.
- Have those paths tell the logger which song row went live. Stamp LYRICS lines with that `songId`
  while the live section's title and number match it.
- Find out why 2026-09-30's go-lives never reached `StatisticsManager.recordSongDisplay`, and
  fix it if it's a missed path.

**4. Learn the STT session id when STT connects.**
- `TrainingDataLogger.sessionId` is only set at the first Bible detection
  (`BibleViewModelDetection.kt:106-108`), so a service's opening songs land in the `runStamp`
  file.
- STT's `GET /api/health` already returns `session_id`.
- Have `STTManager` read it on connect and on its existing poll (it changes when STT starts a new
  session), and set it for both loggers.
- Keep the detection path as a fallback. The Bible logs benefit too.

**Rules (`AGENT.md`):**
- **Branch:** new branch from `main`; the checkout's `housekeeping` branch has unrelated
  changes. One branch, one PR, no co-author or "generated with" lines anywhere.
- **UI:** none needed. If you add any, stop for approval before tests and screenshots.
- **Tests:** mirror `TrainingDataLoggerTest`, covering:
  - one valid JSON object per line, and de-duplication;
  - session-keyed vs `runStamp` naming, with the header written once;
  - the 30-day sweep removing `live-content-*`;
  - `songId` stamping from each go-live path;
  - `STTManager` taking `session_id` from a fake `/api/health`.

  Use `testPort(...)`, no fixed ports, no `Thread.sleep`, under ~1 s each. Isolate
  `user.home`. Each new test must pass three times with `--rerun-tasks`.
- **Finish with:** `./gradlew :composeApp:jvmTest --tests '<patterns>'`, `bash test-changed.sh`,
  and `./gradlew :composeApp:detekt` last. Never `detektBaseline`, and never add baseline
  entries.
- **Strings:** none expected. Any new string goes in English `values/strings.xml` only.
- **Docs:** a short note on the file format in `FEATURES.md`, next to the Bible training logs.

**5. Two small API fixes SongListener needs (same PR is fine).**
- Add `songbook` (and `songId`) to `LiveStateDto` (`server/CompanionApiDtos.kt:179`), filled for
  LYRICS from the song row that went live (item 3). Without it a live client can't tell which of
  two editions of a hymn is on screen: "Песнь Возрождения 2001" and "3300" share numbers and
  titles but not words.
- `GET /api/songs/{identifier}` with `_` and `?title=` (InfoAndSongRoutes.kt:116–146) returns
  the *first unnumbered song* rather than the titled one, because `"" == s.number` matches every
  song with a blank number before the title is considered. Match the number only when one was
  given, and add a regression test.

