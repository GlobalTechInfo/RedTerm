# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [v2.4.1]

### Added

- **Workspaces**: bundled shells, servers, theme and snippets that open in one tap (Settings → More tools → Workspaces).
- **Startup-hook GUI**: edit the guest startup hook written into a distro (Settings → More tools → Startup hook).
- **Snippet library**: save commands and paste them into any session (Settings → More tools → Snippets).
- **Distro dashboard**: per-distro version, size, updates and disk (Settings → More tools → Distro dashboard).
- **SSH tunnel manager**: open and close standalone `ssh -N` tunnels that keep a server's saved
  forwards open with no terminal in front of them. Reached from the SSH screen.
- **SFTP transfer queue**: parallel, resumable SFTP transfers. Reached from the SFTP browser's menu.
- **Scheduled guest jobs**: run guest commands on a schedule (Settings → More tools → Scheduled jobs).
- **Local port forward**: open device ports that reach a target, any number at once and across
  any servers. A target is either the device itself (a guest service bound to `127.0.0.1` in a
  proot distro) or a saved SSH server, in which case the forward rides `ssh -N -L` and the
  remote host:port is reached through the connection. Reached from the SSH screen.
- **Recording index**: recordings are listed newest-first and searchable on the Recordings screen.

### Changed

- **Settings**: eight feature rows consolidated into a single "More tools" hub.

### Fixed

- **Termux:API bridge stop**: the redterm-api listener stops cleanly when a session ends instead of lingering.

## [v2.4.0]

### Added

- **A Termux:API bridge of RedTerm's own.** A command run inside a proot session can now
  reach an Android feature in the app, over the same wire protocol Termux:API speaks — an
  abstract Unix socket, the text of a fake `am` command line, and a uint16 length-prefixed
  answer on the same connection. The guest half is a small C helper built for all four
  architectures and extracted per-ABI in app storage, the same way the bundled `ssh` binary
  already is; the app half is a listener that starts with the process and one feature object
  per API. The command names and the grammar are Termux:API's on purpose, so a script
  written for `termux-api` runs in a RedTerm distro unchanged — but the shell, the helper and
  the features are all in this app, and nothing is handed to another package.
- **The command surface, trimmed to the build's permission profile.** Each
  command is installed under **both** a `redterm-` name (RedTerm's own, e.g.
  `redterm-battery-status`) and a `termux-` name (so a script already written
  for Termux:API keeps working). This build trims its manifest to a
  defensible core so it installs cleanly with Play Protect on (see Fixed
  below), so the commands that need the permissions the trim removes —
  SMS, calls and call log, a location fix, a still capture, torch, NFC,
  infrared, fingerprint, wallpaper, the Wi-Fi scan list, and the SSID —
  are not part of the shipped surface; the rest are: battery, volume,
  vibrate, brightness, audio info, clipboard, toast, notification
  (post/list/remove/clear), notification channels, share, download,
  media player, media scan, microphone record, Wi-Fi connection
  info, network CIDRs/interfaces, USB list, sensors (inventory and a
  bounded read), text-to-speech (speak/engines), speech-to-text,
  keystore (create/sign/verify/destroy on `AndroidKeyStore`), job
  scheduler (on `WorkManager`), the SAF suite (grant/dirs/ls/read/
  write/rm/create), storage get, dialog
  (text/confirm/spinner/radio/checkbox/counter/date/time), callback,
  and the API lifecycle commands. A command whose permission this build
  does not declare answers "not available in this build" rather than
  dying in a pipe.
- **A blocking operation is a real answer, not a dead pipe.** A dialog,
  a speech transcript, and a scheduled job each wait on something a
  person or the hardware decides, which a one-shot socket cannot do by
  itself. Each opens a transparent host activity, the request thread
  blocks on a slot registry, and the answer goes back on the same
  connection — bounded by a timeout, so a dismissed prompt can never
  hold a request thread open.
- **API permissions in Settings.** One card lists the runtime grant the
  bridge can need in this build — notifications — with its state and the
  command that uses it, and one button asks for the ones that are
  missing. A gated command that is refused names the permission and
  points at that card; a command whose permission the build does not
  declare says so instead of pointing at a row that is not there.
- **The bridge is a toggle the app owns.** `termux-api-start` brings the
  listener up (a no-op when it is already running), `termux-api-stop`
  takes it down, and the next start brings it back — the app decides, not
  a background service the user cannot see.
- **A persistent user startup hook, separate from the generated
  `/root/.startup`.** RedTerm rewrites `/root/.startup` on every new
  proot session, so a hand edit there does not survive. An optional,
  user-managed `/root/.startup.local` now runs once, when a new
  interactive session opens, before the interactive shell starts: the
  launcher marks the interactive tail (`REDTERM_INTERACTIVE_SESSION`),
  the generated startup runs the hook as a separate process when that
  marker is set and the file exists, and skips it otherwise. A missing
  file is a silent no-op; a non-zero exit is reported (`RedTerm:
  /root/.startup.local reported an error (exit N) — continuing the
  session`) without ending the session; one-shot commands never run it;
  and the file is user-owned — RedTerm regenerates `/root/.startup` but
  never the hook, so it survives session restarts and app updates. This
  is the requested persistent initialization point for distro startup
  tasks such as Debian's dynamic MOTD (`run-parts /etc/update-motd.d`).
  The mechanism is distro-agnostic — the hook block is in the shared tail
  of the generated startup for all twenty distros, not Debian only. A new
  guest command `redterm-startup-local` (pure guest-side, no bridge)
  prints a safe starter body for the hook: its Debian-family MOTD line is
  double-guarded so it is a no-op on the other nineteen, so one printed
  body is correct everywhere. Pipe it to create the hook:
  `redterm-startup-local > /root/.startup.local`. Fixes #32.

### Commands

Every command below is installed in the distro's `/usr/local/bin` under **both** a
`redterm-` and a `termux-` name (same body), so existing Termux:API scripts keep
running. Commands with structured output write JSON to stdout. Parser errors and
refused commands write plain text. A refusal may report a declared-but-missing
grant, or a permission this build does not declare at all. After the response
is received, the helper exits with status 0 — the exit status reports transport,
never the command's outcome, so the text is the answer. A missing grant on a
declared permission is fixed in Settings; an undeclared one cannot be enabled
there, and the refusal says so.

**Bridge control**

- `redterm-api <args…>` — generic pass-through to the app's action handler (`-a <action> --es/--ei/--ef/--esa …`)
- `redterm-api-start` / `redterm-api-stop` — bring the app-side listener up / down
- `redterm-download <url> [-d desc] [-t title] [-p path]`
- `redterm-callback <url> [-d data] [-m METHOD] [-t secs]`

**Guest tools** (installed under `redterm-` only — no `termux-` alias, no bridge round-trip)

- `redterm-startup-local` — print a safe starter body for the user-managed
  `/root/.startup.local` session hook (#32). Create the hook with
  `redterm-startup-local > /root/.startup.local`.

**Device state**

- `redterm-battery-status` — level, status, health, plugged, temp, voltage, current
- `redterm-audio-info` — playback/recording device info
- `redterm-telephony-deviceinfo` — `device_id` (IMEI/MEID) and `sim_serial_number` from the subscription (read reflectively; privileged identifiers answer only where the OS allows)
- `redterm-telephony-cellinfo` — current + neighbouring cells
- `redterm-wifi-connectioninfo` — active connection (IP, speed, BSSID, frequency; the SSID is masked without a location grant)
- `redterm-wifi-enable true|false`
- `redterm-network-cidrs` / `redterm-network-interface`
- `redterm-storage-get <path>` — stat a path on the device
- `redterm-sensor` (list) / `redterm-sensor -s <name> [-n count] [-d ms]` (read)
- `redterm-usb -l` / `-p <dev|vid pid>` / `-o <dev> [vid pid]` / `-f <dev> <file>`
- `redterm-camera-info`
- `redterm-keystore list|create|sign|verify|destroy [alias] [-a alg] [-s size] [-c curve] [-d data] [-g sig] [--detailed]`

**Controls**

- `redterm-volume [-p|-d|-i|-m|-u] [-s stream] [-f value]`
- `redterm-vibrate [-d ms] [-P pattern]`
- `redterm-brightness [0..255]`
- `redterm-notification [-t title] [-m msg] [-i id] [-r|--remove | -c|--clear]`
- `redterm-notification-list` / `redterm-notification-remove [-i id]`
- `redterm-notification-channel <id> [name]` / `-d <id>` (create / delete)
- `redterm-toast "text"`
- `redterm-clipboard-set "text" | -f file` / `redterm-clipboard-get`
- `redterm-media-scan [-r] [-v] <path…>`
- `redterm-dialog <confirm|checkbox|counter|date|radio|sheet|spinner|text|time> [-i hint] [-t title] [-v values] [-r range] [-d datefmt] [-m] [-p] [-n]` — blocking, answers on stdout
- `redterm-job-scheduler [--script sh] [--period-ms N] [--network any|metered|unmetered] [--battery-not-low] [--storage-not-low] [--charging] [--persisted] [--cancel] [--cancel-all] [--job-id N]`
- `redterm-contact-list [name]`
- `redterm-share <text…> [-d mime] [-s subject] [-f files]`
- `redterm-tts-speak [-e engine] [-l lang] [-n region] [-v variant] [-p pitch] [-r rate] [-s stream] [text | stdin]` / `redterm-tts-engines [-e engine]`
- `redterm-media-player play [file] | info | pause | stop`
- `redterm-microphone-record [-f file [-l secs] [-e encoder] [-b kbps] [-r rate] [-c channels]] [-i] [-q]`
- `redterm-speech-to-text [-l lang] < audio`

**Storage access framework**

- `redterm-saf-grant` — opens the system tree picker and persists the read+write
  grant for it; prints the tree URI. Every other `saf-*` command is only as
  usable as the trees this has granted.
- `redterm-saf-create <tree-uri> <filename>` / `redterm-saf-dirs` / `redterm-saf-ls <tree-uri>`
- `redterm-saf-read <document-uri>` / `redterm-saf-rm <document-uri>` / `redterm-saf-write <document-uri> < data`

  ### Fixed

- **Play Protect flagged the APK as "harmful" and it would not install with the
  scanner on.** The v2.3.x manifest declared ten benign permissions (internet,
  vibrate, wakelock, notifications, storage) — a clean terminal/SSH profile. The
  API bridge added the union of `READ_SMS` + `SEND_SMS` + `READ_CALL_LOG` +
  `CALL_PHONE` + `READ_CONTACTS` + `ACCESS_FINE/COARSE_LOCATION` +
  `RECORD_AUDIO` + `CAMERA` + `READ_PHONE_STATE` + `WRITE_SETTINGS` +
  `SET_WALLPAPER` + infrared/NFC/biometric — the exact signature of an
  SMS-stealer / phone-jacking trojan, and no legitimate terminal ships it.
  Play Protect reads that static union and flags the app.
- **The manifest was trimmed to a defensible core so the profile no longer
  reads "malware".** The bridge now declares only `ACCESS_WIFI_STATE` for
  itself (plus the all-files access its /sdcard backups need); the SMS / call /
  call-log / contact / location / microphone / camera / phone-state /
  write-settings / wallpaper / infrared / NFC / biometric permissions were
  dropped. The affected bridge commands do not silently break: the dispatch
  gate distinguishes a missing grant from an *undeclared* permission and
  answers "not available in this build" instead of pointing at a Settings row
  that no longer exists. The simple privileged accessors (`getScanResults`,
  `getAllCellInfo`, `getDataNetworkType`, `setBitmap`, `clearWallpaper`) are
  now reached reflectively — the same device-only-access pattern the codebase
  already uses for `getConnectionInfo`/`getImei` — so a build that re-declares
  and grants a permission makes the command work again with no code change,
  and the static check cannot flag an untrackable call. The three
  callback-based ones (a location fix, the biometric prompt, a still capture)
  become honest "not available in this build" answers, since a trimmed
  permission can never be granted so the calls could only throw.
- **`versionCode 8` / `versionName "2.4.0"`** were set on the release.
- **Settings → API-permissions card wore the other app's name.** The hint read
  "the termux:API bridge" and every "used by …" line named a `termux-*`
  command. It now says the RedTerm API bridge and names the `redterm-*`
  command (the guest installs both spellings, but the app's UI is RedTerm,
   not termux); the two error strings that pointed at `termux-microphone-record`
   and `termux-usb` now point at `redterm-*`.
- **`redterm-telephony-deviceinfo` reflected the same wrong target.** `getImei`
  / `getSubscriberId` are now reached on the declared `TelephonyManager`
  (with the legacy `getDeviceId` fallback for pre-26), so the `<withheld>`
   marker is the OS answering, not a lookup failure dressed up as one.

- **`redterm-telephony-deviceinfo` looked like a network command.** It is the
  radio-identity command, and its two identifier fields (`device_id`,
  `sim_serial_number`) are IMEI/IMSI, which the OS withholds from every app
  since Android 11 — that `<withheld>` is the platform's answer, not a
  failure. The command now also returns the device fields that need no
   permission at all (`device_model`, `device_manufacturer`, `device_brand`,
   `device_name`, `device_board`, `android_version`), so it answers "device
   info" with real device data instead of only radio fields.
- **`redterm-media-scan` warned `does not exist: /`.** An empty `esa` element
  (an empty `PATHS` still arrives as one empty string) became `File("")`,
  which resolves to the process cwd — a warning about `/` that pointed at
  nothing. Empty elements are now filtered, and the warning names the path
  actually requested.
- **`redterm-media-scan`'s path join only worked under GNU sed.** The guest
  script built the comma list with the `:a;N;$!ba` join idiom, which
  busybox-style seds answer with an empty value — so the app received one
  empty element and had nothing to scan. The join is now a portable shell
   loop; a path containing a comma is still escaped for the array split.
- **`redterm-network-interface` answered with one interface's name — and
  it was `dummy0`.** The first UP interface by enumeration order is not
  the one meant by "the network". The op now lists every address of every
   UP interface, Termux:API's `inet`/`inet6` line shape.
- **`redterm-saf-dirs` crashed on a provider `SecurityException`.** It queried
  the external-storage provider's roots URI, which recent Android refuses
  outright when no tree has been granted, so the command failed instead of
  answering. It now lists the app's persisted URI grants — the set the
  document operations are actually allowed to use — and the document
  operations return a readable "grant it with `redterm-saf-grant`" instead of
  a raw `SecurityException`. The new `redterm-saf-grant` command is what
  makes that set non-empty: it opens the system tree picker and persists the
   read+write grant on the spot.

- **The helper was installed under a name no script could reach.** It was extracted as
  `<arch>:<version>` inside the directory bound to `/usr/local/redterm`, while every script
  executes `/usr/local/redterm/redterm-api` — so the first command a user ran said
  `not found`. The binary now keeps the fixed name
  `redterm-api` at both ends, and the ABI and version live in a separate stamp file, so the
  path the scripts use is the path the bind provides. On a 32-bit arm device the JVM reports
  `armv7l` while the asset is `armv7a`, so the two names are joined explicitly instead of
  assumed. All four shipped ABIs (`aarch64`, `armv7a`, `x86_64`, `i686`) are in the APK;
  each device selects its own and the log line shows which one was chosen.
- **The guard tested the exec bit, and app storage is `noexec`.** Every guest script checked
  `[ ! -x /usr/local/redterm/redterm-api ]`, but `access(X_OK)` on a file in app-private
  storage fails on Android 12+ regardless of the file's mode — so with the helper
  correctly installed, *every* command died at the guard with 127 before it ever reached
  proot's loader (which is what makes noexec storage runnable at all). The guard now tests
  existence (`-e`, which is what "is it installed" actually means) and hands the exec to
  proot. Measured on device: `session "Alpine" exited with status 127` on every command.
- **The launcher spoke upstream proot's dialect to a termux fork — and then the
  fix landed on an orphan line.** The two-word bind `-b <host> <guest>` was parsed by the
  bundled proot as a self-bind of `<host>` plus the command `<guest>`, so a session's proot
  tried to exec `/usr/local/redterm` inside the rootfs and died 127 before any shell.
  Rewriting it as the fork's one-argument `host:guest` form (as proot-distro's own
  `--bind=…:/…` lines do) was not enough: the new bind was emitted on its *own line*, and
  the `exec proot …` statement continues across lines only through trailing backslashes —
  so the `exec` ended at the line of the ordinary binds, and the api bind plus the guest
  command sat on lines the shell never reached. Proot then started with the default
  shell and no api bind at all, while the app log said the bridge was on and the launcher
  even contained the correct line. The bind now shares the binds' line (one long
  continuation), the guest-side target is `mkdir -p`'d in the rootfs as proot-distro
  does for its fake /proc and /sys, and the launcher test now pins that the api bind and
  the last ordinary bind are on the same line ending in a backslash — the regression
   this session shipped.
- **The helper was a static binary, and bionic aborts static binaries on the TLS
  segment's alignment.** Once the bind finally worked, every command aborted with
  `executable's TLS segment is underaligned: alignment is 8 … needs to be at least 64
  for ARM64 Bionic`: bionic's static-TLS layout gate rejects a static executable whose
  `PT_TLS` is underaligned, and the NDK's static link emits 8. The 16 KB-era NDK makes
  the static path a dead end for a proot-executed binary anyway (its bionic `.bss` is
  16 KB aligned, which breaks 4 KB-page devices), so the helper now links dynamically
  against bionic's shared libraries — the exact recipe `build-mosh.sh` ships for the
  mosh client: 4 KB `LOAD` segments, `INTERP /system/bin/linker64` (which the guest
  reaches through the `/system` bind), and the dynamic linker's own TLS setup instead
  of the static gate. All four ABIs rebuilt and the build verifies the 4 KB alignment;
  the version stamp moves to 4 so devices re-extract.
- **A broken bridge now says so, in both places it is checked.** The app logs the bridge's
  state when it prepares a session (`on: helper bound at /usr/local/redterm`, or `off: no
  API helper available` plus the install failure), and every guest script now guards its
  `exec`: if the helper is missing the command prints what is missing and points at the
  app's Diagnostics screen instead of the shell's bare `not found`, and exits 127.
- **A numeric extra reached the app quoted, and a quoted one did not parse.** The guest
  helper wrapped every value in quotes, while the app's parser read bare numbers for
  `--ei`/`--ez`/`--ef`: every integer, boolean and float would have failed to parse or been
  dropped, and the only symptom would have been a fallback looking like the device's own
  answer. The helper now quotes only the string forms, and a test drives a real abstract
  socket end to end to read back the bytes that arrive.
- **A backslash in a value corrupted the rest of the request.** Only `\"` was unescaped, so
  a value ending in a backslash made its own option vanish and every extra after it shift by
  one, and a value carrying an embedded backslash was mangled. The parser now unescapes any
  escaped character and scans a value character by character, so a backslash is content and a
  quote inside a value is not a terminator.
- **The guest scripts used Android's shell path.** They were installed with
  `#!/system/bin/sh`, which does not exist inside a distro rootfs, so every command would
  have failed to execute. They use `#!/bin/sh`, which does, and every script is now run
  through a real `sh` in the test suite rather than asserted as text.
- **A blocking command stalled the whole bridge.** The listener handled one connection at a
  time, so a location fix waiting out its timeout held every other guest command behind it.
  Each request now runs on its own thread.
- **A shell variable that the Kotlin string tried to read, and a shell number that read as
  Kotlin.** The strings that build a guest script interpolate `$`, so an unescaped
  `$variable` inside one is a compile error rather than a script the guest runs, and a
  command substitution that produced a number was parsed as a Kotlin expression. Both are
  now escaped or quoted where they belong, and the shell tests execute each script to prove
  it.
- **The whole feature passes the build's zero-warning gate.** Every deprecated or
  withdrawn API the platform still offers a non-deprecated answer for is used through that
  answer — `getScanResults`/`getActiveNetwork` instead of the withdrawn
  `WifiManager.getConnectionInfo`, `WorkManager` instead of `JobScheduler`, the
  `getCurrentLocation` single-fix call instead of the deprecated `requestSingleUpdate` —
  and the two APIs that exist only on a device and not in the build's platform stub
  (the infrared blaster, the privileged radio identifiers) are reached through the service
  lookup and reflection, so the code compiles against the stub and still runs on hardware.

## [v2.3.0]

### Added

- **mosh, as a per-server option.** A saved server can be set to open over mosh instead of
  SSH. mosh keeps no connection at all once the shell is up — it runs over UDP — so a
  session survives switching between WiFi and mobile data, a lift, or a cell handover, all
  of which drop a long-lived TCP connection. The protocol is mosh's own, built from mosh
  1.4.0's libraries and shipped as a per-ABI native client for all four architectures, the
  same way the bundled `ssh` binary already ships.
- **The transport is visible.** The session title and the notification show the transport
  that actually connected, not the one that was requested, so a server whose card says
  *mosh* next to a session saying *· ssh* has made the fallback visible without any extra
  state. See Fixed.
- **Switching transport from a running session.** **⋮ → Connection → Reconnect using mosh**
  or *Reconnect using SSH*, on SSH sessions. It opens a new session over the other protocol
  and closes the old one — the two are separate processes, so one cannot be converted into
  the other — and the server's saved preference moves with it.
- **A connecting indicator** while an SSH or mosh session is brought up, covering the
  plain-SSH connect and the SFTP browser's first connection as well, each with a way
  out if the connection stalls.

### Fixed

- **Recording a session actually records that session.** Recordings used to mean
  "arm it now, and the *next* session you open gets captured" — which often
  happened to be a throwaway, produced a file whose name was guessed at start-up,
  and said nothing when no recording ever recovered the terminal output. Now the
  Record button captures the session it sits on: the emulator's visible screen is
  snapshotted on every drawn frame and re-encoded as plain ANSI (positioned rows
  and SGR state deltas, the same playback the Recordings player already speaks),
  so exactly what the session drew is what replays. Files are written under the
  app's recordings directory in one place, named at start from the session's own
  identity (distro or server), flushed per frame, and cleaned up when a recording
  ends empty — ending with its session, by hand, or when the activity dies. Both
  ends log through AppLog: started, stopped (with frame and byte counts), and why
  a storage failure or a frame write stopped it.
- **Playing a recording no longer crashes the app.** The replay view attached its
  session and never gave it a font, so the first layout ran straight into
  TerminalView's null renderer — the same crash the mosh bootstrap hit, from the
  same missing order. The player now sets the size and typeface before the
  attach, and applies the theme's palette after it, so a recording replays in
  the same colours it was made with. Font and palette helpers moved to a shared
  `TerminalTheming` so every surface showing a terminal uses the same ones. The
  view's client pointer is the next unguarded slot in the same row of machinery
  (`updateSize` calls its `onEmulatorSet` with no guard), so the player answers
  that too — and pinch zooms the replay the way the session terminal does.
- **And replay no longer runs a shell at all.** Feeding the file through a pty
  made playback depend on whichever `stty` vocabulary the device ships: on this
  one the child exits `stty: -icanon: unknown option`, code 1, before a single
  frame is fed in — the replay was a blank page that finished before it started.
  The pty existed only because `TerminalView` needs a session; the emulator does
  not. Playback now feeds chunks of the file into a private `TerminalEmulator`
  on a background thread and draws it with the library's own renderer — no
  process, no stty, nothing device-dependent in the middle.
- **"Press Enter to close this session and open one over SSH" now works.** The
  failed mosh session's report is printed by a process that then exits, so nobody
  was listening for the Enter it asked for: dismissing the failure dialog left a
  dead terminal eating keys. The dialog's dismissal now arms the promise — and an
  Enter aimed at that dead session is intercepted at the terminal client, where
  the library offers both key events and input-method-committed text before it
  types anything — closed, and the same server opens over SSH, without changing
  the mosh preference the user chose to keep.
- **The build is back at zero warnings.** Six compiler warnings from Android 16/17's
  deprecation wave are fixed at the source rather than suppressed: the deprecated
  `TRIM_MEMORY_RUNNING_LOW` threshold (any trim signal now drops a cache that is a
  map clear to rebuild), the deprecated `announceForAccessibility` (the connecting
  overlay's message is now an accessibility live region whose text changes on screen,
  the sanctioned replacement), the deprecated `SHOW_IMPLICIT` flag (deprecated and
  without effect since Android 16; the flag is simply omitted), the Int `versionCode`
  field (`PackageInfoCompat.getLongVersionCode`), and two conditions that compared
  things they could never satisfy — a `File` tested for null and a non-null list's
  element tested for null.
- **`exit` at the remote shell now ends a mosh session.** mosh-server says goodbye
  on its own protocol — encrypted packets that arrive wherever the session's
  packets do, ICMP-filtering NAT and all — and the client never acted on it: by
  the time the shell exits every state is acknowledged, so nothing more is sent,
  the network falls silent in both directions, an error-based detector never
  hears an error, and the session sat at `logout` for ever. Measured before the
  fix, against a real mosh-server: killed after 50 s, still running. After it:
  a clean goodbye, exit 0, no failure text, in a few seconds.
- **A mosh session that never reached the server is covered until it says so.** The
  connecting overlay used to end the moment the bootstrap handed back a listening
  server, on the theory that the client draws its own connecting status — which it
  no longer does. A blocked UDP path then read as a blank terminal for the full
  twenty-second wait. The overlay now ends on the session's first drawn output, the
  failure report or the 30-second cap, exactly as a plain ssh connect is handled.
- **Closing a mosh session that never connected can no longer leave the screen
  behind it stale.** The no-reply dialog's early return skipped the session
  bookkeeping the ordinary close runs — the split teardown, the re-attach to the
  surviving session, the title, the drawer and the home-screen widget — so drawer
  rows could point at sessions that no longer existed. Only the activity's own
  close is skipped while the dialog is up; everything else runs.
- **The no-reply dialog no longer claims a session was opened.** Cancel opens
  nothing, and the buttons open one on choice; the message now says the server can
  be opened over SSH instead.
- **The credential loop stops on a failure no credential can fix.** An attempt
  that is not a refusal — the host unreachable, or it accepted a key and then had
  no mosh-server to run — used to send the remaining keys and the password stage
  at the same question, each a fresh connection and possibly a prompt on a server
  that had already shown its hand. The first such attempt now ends the loop and
  carries its own reason out, instead of the last credential refusal masking it.
- **The native mosh client's error text can no longer be read while it is being
  rewritten.** `mosh_session_last_error` returned a pointer into a string the
  worker keeps reassigning, which a caller formatting the previous failure while
  the next `recv()` threw could read mid-mutation — the remote-exit path does
  exactly that. The text is now copied out under the lock. The client's
  `connected` flag is atomic (the caller's thread reads it while the worker
  writes) and its consecutive-error counter is initialised rather than left
  indeterminate.
- **A font collection is no longer accepted and then hidden.** `.ttc`/`.otc`
  passed the import filter but neither the settings list nor the terminal's font
  loader honours them, so an imported collection appeared in no list and could not
  be removed. They are refused at the picker, where the refusal is readable.
- **Cancelling one connect attempt can no longer spoil another.** The attempts
  shared one abandoned-flag, so a stalled attempt the user cancelled went on to
  attach when a newer attempt had already begun — and could discard the session
  they had just asked for. Results are now tracked per attempt: Cancel invalidates
  only the attempt it was shown for, and a worker attaches only when its token is
  still the newest.
- **The browser's SFTP connection check can no longer freeze the UI.** Asking
  whether a connection is up took the same lock a running transfer holds for its
  whole duration, so a tap that started a load waited behind it. The check now
  takes no lock.
- **A passphrase key further down the list gets its prompt.** An open key's
  refusal — where no dialog can help — was spending the connection's one
  opportunity to ask, so a server that accepted the *second* key, an encrypted
  one, rejected the connection without ever asking for its phrase. Only a
  question actually asked now counts as asked.
- **Cancelling during the attach-wait is not a session failure.** It ended the
  session the overlay was attached to, which arrived as a kill and was then
  reported to the user as "session ended" with status -9 — a dialog for the
  cancel they had just chosen.
- **The connection test's result can actually be copied now.** Long-press selection
  was broken by a movement-method conflict and is fixed; a Copy button has been
  added to the card as well, which puts the whole result on the clipboard in one
  tap without depending on selection working at all.
- **A successful connection test no longer prints a wall of debug trace.** The
  `-v` output that diagnoses a failure is noise on a success; the success card is
  now what the connection used and what answered — the method, the key's label
  where one was used, and the server's own version — and the failure card keeps
  the trace, with lines merely naming the build machine's directories filtered
  out of it.
- **The passphrase hint on the test card is shown when it is true.** It used to
  appear on a success, saying the check "can only pass for a key without one" —
  after a check that had just passed with a passphrase key. It now appears on a
  refusal, and says both things a refusal can mean: a passphrase that never
  arrived, or a key the server has no record of.
- **The SSH connecting indicator now covers the actual connection.** It used to end
  when the session process started, which is the fastest part: TCP, key exchange and
  authentication happen afterwards, on a blank terminal. The indicator now stays
  until the session draws its first output — the banner, the prompt, or ssh's own
  passphrase question — with a 30-second cap so a connect that produces nothing
  cannot trap the user behind it. Cancelling while it is up also stops the session
  it was attached to, including during that wait.
- **A key made with a passphrase now works everywhere, and asking for it stopped
  going wrong.** The connection test and file transfer failed on such keys without
  ever offering the prompt, for two reasons: a passphrase question dismissed once
  was remembered in process-wide state and every later connection kept failing
  without asking again, and a phrase held in memory was treated as proof that no
  question could help — so one mistyped passphrase silenced asking for the rest of
  the app's life. Both are gone: a dismissed prompt belongs only to the connection
  attempt it interrupted, and on refusal the key file itself is probed locally —
  an open key can never make a passphrase work, so it is never asked about; an
  encrypted key is offered its dialog on every connection attempt.
- **Font import works on real devices.** The document picker filtered with `font/*`, but
  providers report the same font under many names — `application/x-font-ttf`,
  `application/font-sfnt`, or plain `application/octet-stream` for anything a browser
  downloaded — so every folder containing fonts opened empty and nothing could ever be
  selected. The picker now opens unfiltered, and what is picked is validated instead by the
  display name the provider records; a non-font file is named in a message rather than
  imported as one. `ttc`/`otc` collections are accepted where the old import would have
  rewritten them to `.ttf`.
- **A failed mosh session no longer leaves an empty terminal.** It opens over SSH instead and
  says why, offering either to keep asking for mosh next time or to stop asking for this
  server. Nothing about it is silent: a fallback with no explanation is indistinguishable
  from the preference having been ignored.
- **`exit` at the remote shell ends the session.** `mosh-server` closes its UDP port, the
  next packet draws ICMP port-unreachable and every receive fails; the client counted those
  and now treats several in a row with no successful receive as the far end saying the
  session is over. Previously the terminal printed `logout` and then sat there for ever.
- **A live but quiet session is no longer at risk.** The earlier fix for the above removed an
  idle timeout, which had the opposite failure — a mosh session is silent most of the time
  somebody is reading a man page, and killing it for being quiet would break the one thing
  mosh exists for. There is now one timer, and it is about waiting for the first packet.

### Known limits

- **mosh needs UDP 60000–61005 open to the server, all the way through**, which means the
  cloud provider's security list *and* the host's own firewall. Cloud images commonly ship a
  ruleset allowing TCP 22 and rejecting everything else, and opening the port in the
  provider's console alone is not enough.
- **A phone behind carrier NAT may not be able to receive the UDP at all.** SSH works in
  both cases; only mosh needs the return path. mosh over an overlay network such as Tailscale
  usually works regardless, because the traffic travels inside a connection the phone
  already opened.

## [v2.2.0]

### Highlights

- **A freshly installed distro now keeps the permissions the distribution chose.** Installing
  used to flatten every directory to `0755`, so `/tmp` stopped being world-writable with its
  sticky bit, `/root` was loosened, and every pacman transaction on Arch and ARMtix reported
  the difference:
  `warning: directory permissions differ on /tmp/ filesystem: 755 package: 1777`.
- **openSUSE is no longer reconfigured on every launch.** The repository aliases, the libzypp
  service and `RepoManager.db` are all left exactly as the image ships them, because they were
  never the fault.
- **Four distributions were silently running with none of the setup applied.** Oracle, Fedora,
  AlmaLinux and Rocky ship a `/root` this app could not write into, so the prompt and the whole
  first-time setup had never landed on them. See Fixed.
- **Twelve more distributions**, bringing the catalogue to twenty, all built from upstream by
  this project and checksum-verified before installation.
- **SFTP is documented as what it is** — a home-screen tool that browses a saved server
  without a distribution installed.

### Fixed

- **Installing a distro discarded the file modes in the image.** The install path `chmod`'d
  every directory to `0755` as it created it and reduced every file to "executable or not" —
  it read the mode out of the archive and then threw it away. Restore has always preserved
  modes correctly; install had its own extractor and it was the one that was wrong. The
  published images were correct throughout, so no change to how they are built could ever have
  fixed it.
- **A setgid directory lost its bit.** The mask now keeps setuid, setgid and sticky instead of
  discarding them, and directory modes are applied after their contents rather than on
  arrival — a directory the archive records as `0555` cannot have children created inside it
  if its mode is set first.
- **The post-install permission repair is gone.** It existed only to undo the bug above, and
  could only ever agree with the flattening because it had no other mode to work from.
- **Arch and ARMtix images applied `DisableSandbox` twice**, which pacman reports as a
  duplicate directive on every command.
- **openSUSE's startup script was rewriting working configuration.** Alias rewriting, file
  renaming, service removal and `RepoManager.db` deletion were each applied across several
  releases, fixed nothing, and cost the user repositories they could install from. Verified by
  running `zypper refresh` natively against both this project's published image and
  proot-distro's, whose repository files are byte-identical: both succeed with the stock
  configuration and build the very cache path that had been reported as impossible.
- **Four distributions never received any of it.** Oracle, Fedora, AlmaLinux and Rocky ship
  `/root` as `0550` — no owner-write bit at all — and on Android the extracted tree belongs to
  the app's own uid, so those owner bits are the ones that apply. Every write into `/root`
  failed with `EACCES`: the prompt, `.bash_profile` and `.startup`, and with `.startup` goes
  the entire first-time setup. The session-writing path swallowed this into a log line that
  Diagnostics does not show, so those four came up silently unconfigured; applying a shell
  template crashed instead. The write bit is now borrowed for the one file being written and
  the mode restored afterwards, so `/root` is left as the distribution intended.
- **Reinstalling a distribution failed with `EACCES`.** `cadir` is recorded read-only, so the
  first install left a tree the second could not clear: unlinking a child needs write access
  to its parent. The result of deleting the old rootfs was ignored, so extraction continued
  into the old tree and wrote into files it could not open —
  `.../cadir/ACCVRAIZ1.pem: open failed: EACCES`. Which is why the first install worked,
  reinstalling did not, and the obvious response to the error was the thing causing it.
- **Hard links in an image were extracted as empty files.** The installer had no branch for
  type `1` entries at all, so anything a distribution hard-links came back zero-length. They
  are now materialised as independent copies, which is also what proot wants, since it
  emulates `link()` and a real hard link would alias inodes the guest treats as separate.
  Restoring now keeps the linked file's mode and resolves both ways a link target is written
  — a bare sibling name, and a path from the archive root.
- **Arch asked for database signatures that no mirror serves.** The images shipped
  `SigLevel = Required DatabaseOptional`, so pacman requested `<repo>.db.sig`; the databases
  download fine and the signature beside them does not exist. With `curl -f` as the transfer
  command that is a hard error, and every pacman command on every repository printed
  `curl: (22) The requested URL returned error: 404`. Package signatures remain required.
- **A step warned about a `gcc-libs` conflict that did not exist.** It ran on every first
  launch of Arch and Manjaro and could only ever fail there. Arch has already split the
  runtime into `libgcc`/`libstdc++`, and Manjaro never split it at all, so there is nothing
  to repair on either. Five tests asserted the step's presence, one of them as a requirement
  that it exist; it is gone, and an assertion of absence replaces them.

### Changed

- **First launch no longer upgrades anything.** No image runs a package upgrade on your
  behalf; updates are started by you, from **Package Updates** or from the terminal.
- **openSUSE's cache is made writable, and nothing else.** A session runs as the app's own uid
  while the image builds that cache as root, so a cache it cannot write is one it cannot
  build. That is now baked into the image as well, so a new image works on its own rather than
  waiting for a particular build of the app to repair it.
- **Images come from this project's own builds**, published by
  [GlobalTechInfo/rootfs-images](https://github.com/GlobalTechInfo/rootfs-images), rather than
  from Termux's proot-distro. Every image carries a recorded upstream version and the recipe
  that produced it.
- **A wrong comment about `find -perm -400`** in the image builder, which described the
  predicate as testing read permission when it tests the owner's execute bit.

### Added

- **Twelve distributions:** Adelie, ARMtix, Chimera, Gentoo, Oracle, Pardus, Parrot, Rocky,
  Slackware, Trisquel, and additional architectures for the existing ones.
- **`docs/proot-distro-reference.md`**, recording what proot-distro's own install and recipes
  actually do, with the measurements behind each claim and how to reproduce them.

### Notes for existing installs

- The permission fix applies at extraction. A distro installed with an earlier build already
  has `/tmp` at `0755` on disk and will keep it — **delete and reinstall the distro** to pick
  this up. Reinstalling the app alone changes nothing.

## [v2.1.0]

### Highlights

- **SFTP file browser.** Browse, download and upload files on a saved SSH server without
  typing a single command. Runs over the bundled OpenSSH client, so it needs nothing
  installed.
- **Backup and restore now work on every distro.** Previously any distribution containing
  hard links — Arch and Manjaro in particular — could not be restored at all, and a backup
  could quietly leave out files it was not permitted to read, such as `/etc/shadow`.
- **Themes now apply to the terminal**, not just the app around it. Switching theme
  re-themes a running session immediately, and the Light theme is legible.
- **Distro logos** on the install, home, settings and backup screens.

### Added

- **SFTP browser** (**SSH → Browse files**): the same file manager used inside a distro,
  pointed at a remote server. Navigate, upload, download, rename, delete, create folders
  and search, plus the remote-only extras — change permissions, create symlinks, and see
  free space. Progress is reported in bytes as transfers run.
- **SSH keys** are now a proper keyring: several named keys, generated as ed25519, ECDSA or
  RSA-4096, optionally passphrase-protected, importable from an existing private key, and
  bindable to a server so it is offered automatically. A passphrase is asked once per key
  and remembered for the session, so you are not prompted again for every listing.
- **Server options** per saved host: keepalive interval and count, compression, agent
  forwarding, a jump host, and local or remote port forwards.
- **Trusted hosts** screen, listing the fingerprints the app has accepted and able to
  forget one.
- **Import an OpenSSH `config`**, including its `Host` blocks, and **import an existing
  private key** rather than only generating new ones.
- **Password authentication** for servers without a key, stored in the Android keystore.
- **Sessions survive the app being closed** and are restored on the next launch.
- **Long-running sessions** option, keeping the terminal and the device awake while a
  session is open.
- **Record the next session** from the terminal quick panel, so a recording no longer needs
  a command typed out by hand.
- **Progress bars** for backup and restore, which take minutes on a real distribution and
  were previously indistinguishable from a hang.
- **Copyable failure output.** A backup or restore failure can be selected and copied out
  of the dialog, and every restore outcome is recorded in **Diagnostics**.
- Backup and restore now **report anything they could not include** instead of quietly
  leaving it out.

### Fixed

- **Backup failed on any distro containing hard links.** Arch and Manjaro could be backed
  up but never restored; Void appeared to work only because it happened to have none in the
  affected place.
- **Backup could omit files it could not read.** A distribution image ships files only root
  may read, and RedTerm does not run as root, so a backup could be missing `/etc/shadow`
  while reporting success — and restoring it gave a distro where login cannot work.
- **A truncated or damaged archive is now refused** instead of being restored into a
  half-populated installation.
- **`bash: fg: no job control` on login**, on distros that do not ship their own bash
  configuration.
- **The shell prompt was invisible on every theme except the default**, and after changing
  theme while a session was running.
- **The Light theme's terminal was unreadable**, appearing blank because the text stayed
  dark-on-dark.
- **Buttons wrapped one word per line** on narrow screens, turning short labels into tall
  ovals.
- **Recording no longer needs a command typed by hand**, and the list gained a Diagnostics
  action.
- The **notification permission** is now requested once during first-run setup, where the
  reason can be explained.
- Distro names are now shown correctly everywhere: `almalinux` was rendered "Almalinux" and
  `opensuse` "Opensuse".

## [v2.0.0]

### Highlights

- **Bundled OpenSSH** for all four architectures. Connect to any host without
  installing a package first.
- **Backup and restore** now round-trip a distribution cleanly, storing only the
  distribution's own files.
- **Soft keyboard behaves as expected**: tap inside the terminal to open it, press
  back to close it.
- **Package management works on Arch and openSUSE** from a fresh install.
- **Landscape layout** on the welcome screen is usable again.
- **Rotating the phone** no longer cancels an install or disturbs the terminal.

### Fixed

- Install progress is no longer cancelled by a configuration change.
- The back button closes the keyboard instead of leaving the terminal unusable.

### Added

- **SSH → Test** runs a non-interactive connection check (`BatchMode`, 10 second connect timeout,
  remote command `true`) and shows the verbose trace, so a failed connection names its cause
  instead of dying silently.


- **Kali Linux**: added as a supported distro, installed from NetHunter's own rootfs on `kali.download` (it is not published by Termux proot-distro). Available on all four architectures.
- **Free-space check before install**: the installer now measures available storage against the space a distro needs and refuses up front with a clear message instead of failing part-way through extraction.
- **Distro diagnostics**: a **Diagnose** action on every installed distro reports on 12 health checks (rootfs layout, root uid, DNS, `bash`, `busybox`, `/bin/sh`, `/usr` permissions, `/tmp`, device nodes, `sudo`, `/root`, size) and can be copied to the clipboard.
- **File manager actions**: every row has a visible **⋮** button for Rename, Copy, Cut, Delete, plus Edit for text files, Open in terminal and Folder info for folders. New folder, paste and refresh actions, a hidden-files toggle, Go to `/root` and a folder size summary.
- **Recursive file search**: search now walks the entire subtree below the current folder and updates as you type, instead of only matching names in the folder you were looking at. Results are capped and it says so when truncated.
- **File manager navigation**: the back arrow and the system back gesture both go up one directory instead of leaving the app, and the arrow keeps the same appearance used everywhere else in the app.
- **Image and video preview**: images open with automatic downscaling and EXIF rotation so photos are not sideways; videos play in place.
- **Text editor**: text files can be edited in the app and saved back into the rootfs. Saving stages a temporary file and renames it over the original, so an interrupted write cannot truncate a config, and the original permission bits are re-applied first so scripts keep their executable bit.
- **Per-distro and multiple sessions**: launching a distro that has no session open now creates one instead of re-showing whatever session happened to be open, and **New Session** offers every installed distro — picking the current one starts a second, independent session.
- **Package updates**: run a distro's own package manager update from the app, with the real output streamed live, and a notification when it finishes.
- **Base image update**: check whether Termux publishes a newer proot-distro image for an installed distro and swap it in, keeping `/root` and `/home`. If anything fails, the previous install is put back.
- **Storage usage**: free and total space on shared storage, plus per-distro the largest directories with proportional bars.
- **SSH**: save servers and connect straight from the app. A full **OpenSSH client is bundled with the app**, cross-compiled for all four ABIs, so a connection needs no distribution installed and does not depend on a distro shipping `openssh-client`. It is unpacked into a small rootfs of its own and run through proot, because Android 12+ mounts app storage `noexec` — the same constraint the distro binaries already work around — so the client is subject to exactly the same rule. Host key checking is on, and an ed25519 key pair can be generated from the SSH screen.
- **ANSI recordings**: record the output of a command with colours and cursor control intact, then replay it in the app. Recordings can be shared or deleted.
- **Backup manager**: a dedicated screen lists backup archives with size and date, and offers restore, share or delete for each, plus cleanup of files left behind by interrupted backups.
- **App log**: Settings → App log keeps a persistent, bounded log of the app. It can be copied to the clipboard, shared, or downloaded to `Downloads/`, and **Capture system log** adds everything the process wrote to logcat, including output from the support libraries, so a failure that ends a process immediately can actually be diagnosed.
- **Completion notifications**: background work (package updates, base image updates, backups, recordings) posts a notification when it finishes.
- **Termux proot-distro rootfs**: all distros now install from the official prebuilt rootfs images published by [termux/proot-distro](https://github.com/termux/proot-distro), extracted and verified in-app.
- **Per-architecture SHA-256 verification**: every distro/arch pair ships a hardcoded checksum; downloads are verified before extraction and a mismatch discards the partial file and asks for a clean retry.
- **Resumable distro downloads**: interrupted downloads resume over HTTP `Range` instead of restarting from zero; a corrupt or server-rejected range falls back to a full re-download automatically.
- **Crash reports**: uncaught exceptions are recorded centrally under Settings → Crash Reports, following Termux's rollover scheme (`crash-report.txt`, then `crash-report2.txt`, … at 4096 characters each). Reports can be viewed and copied in-app, downloaded to `Downloads/RedTerm-crash-report-<timestamp>.txt`, or deleted.
- **File viewer**: tapping a file in the built-in file manager now opens it in a new viewer with monospace text, horizontal scrolling, binary detection, a 2 MB preview cap and a Copy action. Previously files could be listed but never opened.
- **Home button on the welcome screen**: a Home icon now sits opposite Settings, so Home is reachable before any distro is installed.
- **openSUSE**: added as a supported distro.
- **Settings section icons**: seven new vector drawables (`ic_section_distro`, `ic_section_appearance`, `ic_section_font`, `ic_section_terminal`, `ic_section_power`, `ic_section_extra_keys`, `ic_section_app_lock`) shown next to each settings section header.
- **Hardcoded strings extracted**: all hardcoded text in XML layouts moved to `strings.xml` (~90 string resources), enabling full localization.
- **RTL symmetry**: settings layout uses symmetric horizontal padding for proper right-to-left support.
- **Plurals support**: `match_count_for_query` converted from `<string>` to `<plurals>` with proper `getQuantityString()` in code.
- **`KILL_BACKGROUND_PROCESSES` permission**: added to manifest.
- **`ToolbarTitle` theme**: new `TextAppearance` style for toolbar titles (single-line, ellipsize end).

### Changed

- **Rootfs source**: distro downloads point at `termux/proot-distro` release assets instead of self-hosted tarballs.
- **Distro lineup**: openSUSE added. Artix is no longer offered; Kali returns as a NetHunter rootfs (see Added).
- **First-time distro setup**: the setup script now installs `bash` and `sudo` with each distro's own package manager, `unset`s `ENV` and falls back to the distro's own `/bin/sh` when `bash` is unavailable. Alpine previously had no `bash`, leaving users in Android's `mksh` where `bash` was "inaccessible or not found". The script is version-stamped and regenerated on existing installs, and failures no longer abort the whole setup.
- **Per-distro setup correctness**: Arch runs a full `pacman -Syyu` upgrade (a partial `pacman -Sy` against a prebuilt rootfs left packages requiring a newer `GLIBC` than the image shipped); openSUSE clears and recreates `/var/cache/zypp` and disables the x86-only `repo-openh264` repository that broke `solv` cache builds; Fedora/Rocky/AlmaLinux pass `skip_if_unavailable`, lower retries and shorter timeouts so a stale mirror 404 cannot fail the whole run.
- **Shell configs preserved**: `.bashrc` and `.bash_profile` are only written when missing, so user customizations are never overwritten.
- **SHA256 checksums**: populated for every supported distro/architecture rather than omitted.
- **Notification permission flow**: the foreground service is no longer gated on `POST_NOTIFICATIONS` being granted at the moment the activity is created, which meant it was never started and no ongoing notification ever appeared. The service now starts from `onResume` regardless, and the Android 14+ `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` is declared.
- **Rootfs permissions**: extracted directories are `0755` and files `0644`/`0755` (applied with `Os.chmod`), with `umask 022` in the proot launcher. Previously every directory was forced to `0700`, which produced `directory permissions differ on /usr` warnings and blocked some package managers.
- **Distro size display**: computed on a background thread and cached with a 10-minute TTL instead of walking the entire rootfs on the main thread.
- **Terminal toolbar title**: shows only the distro/session name instead of appending the full working-directory path.
- **Version bump**: versionName `1.0.4` → `2.0.0`, targetSdk `35` → `37`.
- **Resource shrinking**: `isShrinkResources = true` enabled in release build.
- **Toolchain**: Gradle `9.7.1` → `9.8.0`.
- **Dependencies**: `core-ktx` `1.18.0` → `1.19.1`, `appcompat` `1.7.0` → `1.8.0`.
- **proot rebuilt from source**: cross-compiled at the current `termux/proot` commit for all four ABIs (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`) and linked with 16 KB page alignment, so RedTerm's own native libraries are loadable on 16 KB-page devices. `native/build-proot.sh` was fixed to install the `libproot*.so` names the app actually loads, build talloc per-ABI, and support all four architectures.
- **Welcome buttons**: changed from `<Button>` to `<MaterialButton>` for proper Material Design styling.
- **Welcome settings button**: simplified from nested `LinearLayout` + `ImageView` to a single `TextView` with `app:drawableEndCompat`.
- **Settings section headers**: now use `app:drawableStartCompat` with tinted icons instead of plain text.
- **Programmatic strings**: all `setText()` calls in Kotlin now use `getString()`/`getQuantityString()` with format strings instead of hardcoded text.
- **Kotlin KTX adoption**: `prefs.edit { }` KTX extension, `.toUri()` extension, `.isVisible` KTX property, and `apply` blocks replaced with `editor.apply { }` throughout.
- **RTL text alignment**: `GravityCompat.END` used instead of hardcoded `gravity="end"` in layouts.
- **Drawable references**: `android:drawableEnd`/`android:drawableTint` replaced with `app:drawableEndCompat`/`app:drawableTint` in welcome layout for backward compatibility.
- **Welcome screen buttons**: removed `buttonBarButtonStyle` attribute (was causing plain-text appearance); Material Button styling now applied directly.

### Fixed

- Opening the app from a shortcut while a session is already open now works.
- Large distributions no longer crash the app during install.
- Notifications for background work now appear and report completion.
- `ESC` and `TAB` extra keys now reach the terminal.
- Pasting into a full-screen program no longer hangs.
- The theme can be changed from the terminal.
- Cancelling a distribution install now cleans up properly and can be resumed.
- Image previews render correctly, and file rows respond to taps.
- Tool screens have a working back button, and extra key rows are aligned.
- A backup is no longer hidden after reinstalling the app.
- A package manager killed mid-update no longer blocks the next one with
  `Could not get lock`.

## [v1.0.4]

### Added

- **RedTerm bash template**: new power-user `.bashrc` template with RedTerm branding, colored prompt, extended aliases, functions and startup banner.

### Changed

- **Toolchain**: Gradle 8.14.5 → 9.7.0, Android Gradle Plugin 8.13.2 → 9.2.1, `compileSdk` 36 → 37; removed explicit `org.jetbrains.kotlin.android` plugin (bundled in AGP 9.x).
- Settings page shows version v1.0.4.

### Removed

- **Exit-status indicator**: the red "✗ Last command failed" strip has been removed; the failure bell remains as the sole command-failure feedback.

### Fixed

- Replaced deprecated `startActivityForResult` with the Activity Result API in Settings.
- Replaced deprecated `ProgressDialog` with `ProgressBar` inside `AlertDialog`.
- Replaced deprecated `Intent.EXTRA_SHORTCUT_*` constants with string literals for home-screen shortcuts.
- Replaced deprecated `InputMethodManager.SHOW_IMPLICIT` with `0`.
- Replaced deprecated `Thread.id` with `Thread.threadId()` in the crash handler.
- Replaced deprecated `VIBRATOR_SERVICE` with service-name strings; uses `VibratorManager` on API 31+ and reflection for `vibrate(Long)` on older APIs.
- Cleaned up deprecated `KeyEvent.ACTION_MULTIPLE` guard in terminal key dispatch.

## [v1.0.3]

### Changed

- **Extra keys**: swapped the positions of the left and right arrow triangles in the default second key row (`◀ ▼ ▶` instead of `▶ ▼ ◀`); Reset in Settings uses the new order too.
- Settings page shows version v1.0.3.

### Fixed

- **Shared storage in distros**: the terminal's proot session now binds `/sdcard`, `/storage` and `/mnt` into the distro, so `/storage/emulated/0` files are visible and usable inside every distro.
- **All files access**: the All files access request moved back to the terminal screen (where it was in earlier builds) instead of the welcome screen, where it could be skipped — the terminal re-asks until it is granted and shows a toast explaining why it is needed.

## [v1.0.2]

### Added

- **Setup permissions**: storage and notification permissions are now requested on the welcome screen during setup instead of when the terminal opens.
- **Custom bash templates**: create your own named `.bashrc` templates in Settings → Bashrc templates, then edit or delete them; deleting a template resets any distro using it back to its original `.bashrc`.
- **Custom fonts**: import your own `.ttf`/`.otf` fonts in Settings — one at a time or several at once from the storage picker — then select them from the font dropdown or the terminal's Fonts menu; custom fonts can be renamed and removed, and names default to the file name without the extension.
- **Modern back button**: the back control is now a round, theme-aware chip; it also appears on the main page and in Settings, with the same style applied in the terminal, file browser and bash templates screens.

### Changed

- **Theme sync**: changing the theme in Settings or from the terminal's Theme menu now broadcasts the change, so the main page and every other screen repaint immediately with the selected theme.
- The terminal's Fonts menu lists imported custom fonts and refreshes every time the menu opens.
- Settings page shows version v1.0.2.
- Settings label renamed to **Bashrc templates**.
- **Toolchain**: Gradle 8.13 → 8.14.5, Kotlin 2.3.0 → 2.4.10, `compileSdk` 35 → 36; dependency updates across androidx (lifecycle 2.8.7 → 2.11.0, activity 1.9.3 → 1.13.0, material 1.12.0 → 1.14.0, constraintlayout 2.2.0 → 2.2.2, appcompat, preference, viewpager2), commons-compress 1.27.1 → 1.28.0 and xz 1.10 → 1.12; `core-ktx` pinned at 1.18.0 (1.19.0 requires AGP 9.1 and compileSdk 37).
- GitHub Actions updated: `setup-android` v3 → v4, `setup-java` v4 → v5.6.0, `gradle/actions/wrapper-validation` v3 → v6.

### Fixed

- **Android 7.0/7.1 support**: the app no longer crashes on API 24–25 at startup (`NotificationChannel` is now only created on Android 8+), and distro extraction/repair no longer calls `java.nio.file` (API 26) or `Process#destroyForcibly` (API 26) unguarded — symlinks are created with `Os.symlink` (API 21+), so first-time setup and busybox repair work on Android 7+.
- CI: official Gradle wrapper jar (checksum validation was failing), compileSdk 36 for the new androidx versions, lint set to non-fatal, docs updated with the current build requirements.

## [v1.0.1]

### Added

- **Bash templates**: pick from eight built-in `.bashrc` templates (Stock, Powerline, Minimal, DevOps, Hacker, Starship, Matrix, Retro CRT) in Settings → Bash templates; applied per-distro, auto-applies directly when only one distro is installed, one template can be applied to multiple distros at once, and each distro can be reset to its original `.bashrc`. The original `.bashrc` is backed up before the first overwrite.
- **Bell/command feedback**: terminal rings (haptic vibration) on BEL, and `PROMPT_COMMAND` rings the bell when a command exits with a non-zero status.
- **Export terminal output**: Copy, Paste and Export buttons in the sessions drawer; exported transcripts are saved to `/sdcard/RedTerm/exports/`.
- **Dynamic session title**: toolbar now shows `distro › /cwd`, updated live while a session runs.
- **Per-distro management**: MainActivity cards now show rootfs size and a long-press menu with Launch / Files / Backup now / Remove.
- **Custom extra keys**: two editable key rows in Settings (space-separated tokens: ESC, TAB, CTRL, ALT, HOME, END, UP/DOWN/LEFT/RIGHT, INS, DEL, BACKSPACE, MENU, `&&` — anything else is typed as text).
- **Open terminal here**: FileBrowser menu action launches a terminal session rooted at the current directory.
- **App lock**: optional 4-8 digit PIN required when opening the app.
- **Settings backup**: Import Config button added next to Export Config (`RedTerm_config.json`).
- **Keep screen on**: the wake lock toggle now also keeps the screen lit while the terminal is open.
- **Widget session count**: widget shows the number of active sessions and a contextual tap hint; a widget config screen lets you pick which distro the widget launches.
- **Split view**: Split button in the quick panel shows two terminal panes side-by-side; extra keys route to the focused pane; split mode collapses automatically when a session exits.
- **FileBrowser search**: search files by name in the current directory with the option to descend into found folders.
- **Terminal find**: highlight all matches in the scrollback, jump and cycle between them.
- **Bundled fonts**: real font files (JetBrains Mono, Fira Code, Source Code Pro, Ubuntu Mono, Droid Sans Mono, Noto Sans Mono, Cascadia Code) shipped in assets and loaded from there.
- **Files browser**: browse the distro rootfs.
- **Home-screen widget**: quick launch a distro from the launcher.
- **Night mode**: automatic switching to AMOLED between 6 PM and 6 AM (toggle in Settings).
- **Session persistence**: sessions survive activity restarts and are resumed on reopen.
- **Distro backup/restore**: improved multi-select backup with progress, restore validation, and backups under `/sdcard/RedTerm/`.
- **CPU indicator**: live CPU usage shown in the service notification.
- **Keyboard shortcuts**: F1-F12 keys emit the proper escape sequences.
- **Output coalescing**: terminal redraws batched via Choreographer for smoother rendering.
- **Tap links and paths**: tapping a URL or a file path in the terminal pops up actions to open it in the browser / file browser or copy it.
- **Quick Settings tile**: a tile that launches the last-used distro straight from the quick settings shade.
- **Home-screen shortcut**: distro menu gains "Home shortcut" to pin a launcher shortcut for that distro.
- **Dynamic (Material You) theme**: new "Dynamic" theme option (Android 12+) that follows the system wallpaper palette, applied to both the app and the terminal colors.

### Changed

- Session persistence is now process-wide; sessions survive activity restarts and are resumed on reopen.
- Session creation, switch and finish flows share a single handler (also used by split view).
- Extra keys are rendered from preferences instead of a hardcoded list.
- Widget configurable per instance via a configuration activity.
- Improved session drawer controls.
- Fonts loaded from app assets instead of the system font directory.
- Base tarballs kept in app files after install so distro reset runs offline; removed on uninstall.
- Shell configs (`.bashrc`, `.bash_profile`, `.startup`) only written when missing, so user customizations are never overwritten.
- Terminal bell toggle in Settings (vibration on BEL / failed commands).
- "Quick settings" and "Split view" entries in the terminal's three-dots menu, so both are discoverable without tapping the top edge of the screen.
- Dynamic theme added to the Settings theme picker and the terminal's Theme menu.
- Split view: each pane now has its own terminal client (live output in both), tapping a pane selects that session (title, drawer highlight, extra keys, CTRL/ALT target it), and the keyboard is no longer force-restarted when switching panes (no more freeze).

### Fixed

- Rootfs `.startup` script now only marks first-time setup complete when package installation succeeds, falling back to a repair shell instead of failing silently.
- CPU indicator integer-division bug that always showed 0%.
- Terminal now renders custom fonts through a shared helper so split panes use the same font.
- Layout-params type mismatch in FileBrowser search bar (LinearLayout params on a LinearLayout child).
- Several Kotlin type-inference issues around key actions.
- Main navigation mix-up where the home button launched Settings instead of Main.
- Terminal activity buttons not respecting theme changes.
- Bash prompt errors (`=0: command not found`, `[: -ne: unary operator expected`) from a fragile `PROMPT_COMMAND`; replaced with a simple `[ $? -eq 0 ] || printf "\a"`.
- Distro reset (long-press card) now restores a fresh state offline: the rootfs is re-extracted from the cached base tarball, wiping installed packages, caches and shell configs; the next launch runs first-time setup again.
- Distro removal now fully removes the distro: rootfs, cached tarball and registry entry are deleted, and any running sessions for that distro are killed, so a later reinstall starts completely fresh.
