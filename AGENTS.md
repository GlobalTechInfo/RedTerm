# RedTerm — agent notes

## What this is

Android terminal emulator + SSH client. Kotlin, single `:app` module, package `com.redtermapp`.
Terminal engine is the upstream Termux libraries pulled from jitpack
(`terminal-emulator`, `terminal-view` v0.118.3). Full SSH lives in the app itself:
real OpenSSH binaries are shipped as per-ABI assets under `app/src/main/assets/ssh/<arch>/`
and executed through proot's `-L` loader (app storage is `noexec` on Android 12+).
No JSch/sshj — there is no second SSH stack.

## Build / verify

```
./gradlew assembleDebug          # build
./gradlew test                   # JVM unit tests (app/src/test)
./gradlew lint                   # gates the build: abortOnError = true
```

**This environment has no JDK, no Android SDK and no NDK.** Builds and `lint` cannot be run
here; the developer must verify locally. `native/build-openssh.sh` cross-compiles the OpenSSH
binaries and must also be run by the developer (it needs the NDK).

## House style

- Terminal/Session machinery must not assume a distro. A session is a `TerminalSession` from
  the engine plus a `SessionDescriptor`; branch on descriptor kind, never on the display string.
- Persist anything that outlives an activity. In-memory singletons do not survive process death.
- Comments explain *why*, and must stay true. Several comments in this repo are already stale
  (`TerminalActivity.launchSsh`, `SshManagerActivity` class doc) — don't add more.
- Secrets never reach `AppLog`. `SshClient.execScript` logs the full command line.
- New user-facing text goes in `app/src/main/res/values/strings.xml`.
- **A failure the user cannot read is a failure nobody can fix.** `AlertDialog.setMessage`
  builds a non-selectable `TextView`, which is fine for "Archive already exists" and
  useless for the several hundred characters of `tar` that explain a failed restore:
  not selectable, not copyable, not scrollable on a phone, and gone on OK. Tool output
  goes through `ToolOutputDialog` — selectable, scrollable, monospaced. A restore failure
  also goes to `AppLog`, because `android.util.Log` is invisible in Diagnostics and the
  dialog is the only copy that gets dismissed.
- **A multi-minute operation needs a progress bar, not just a line of text.** Backing up or
  restoring a real distro takes minutes, and a dialog saying only "backing up" is
  indistinguishable from one that has hung. Both now report a named phase and a fraction.
- **Logging is for failures.** A command that succeeds logs one line; the command, the
  generated launcher and the full output are logged only when it does not. Both used to
  be logged always, which buried the one line that mattered under an identical copy of the
  same launcher script for every listing and every transfer. Every bug in this feature was
  found by reading a verbose trace, so the trace stays — it just is not produced when
  there is nothing to explain.
- Platform noise from a child binary (the `WARNING: linker:` lines every cross-compiled
  executable prints) is filtered out and never enters a failure report, which must contain
  only what ssh actually said.
- **Never lay buttons out with `weight = 1f` side by side.** Weights divide the width
  before anything is measured, so on a phone each label gets a fraction of the screen
  and wraps one word per line — which is exactly how four action buttons became ovals
  with text stacked down them. Buttons keep their natural width and the row wraps:
  `FlowLayout`, with `ScreenWidgets.actionButton` for the buttons themselves, and
  `maxLines = 1` so a label that genuinely cannot fit truncates rather than stacking.
- Tool screens are built in code (`ScreenToolbar.install`, `ScreenColors.cardColor/cardTextColor`,
  `ScreenWidgets.chip`), not layout XML. Only `activity_terminal.xml` and the file-list layouts
  are XML.
- **A status is not another line of body text.** Cards put the label left and the type chip
  right, facts muted under them, and anything about *behaviour* — protected by a passphrase —
  as a chip of its own. A status rendered at the same weight as the creation date reads as
  trivia about when the key was made rather than how it behaves.
- `ValidatedDialog` for any dialog that can be dismissed with unusable input. A positive
  button's listener runs and *then* the dialog dismisses, so validate-show-toast-return
  closes it anyway and the toast is the only trace; the dialog has to stay open with the
  field carrying the error.
- `danger` is a themed colour with a light/dark pair, like `extraKeysBg`. A destructive
  button that looks like the neutral ones beside it is how the wrong thing gets destroyed.

## Active work — the SSH feature overhaul

Agreed plan, all phases to be delivered. Status is tracked below and updated as work lands.

### Phase 0 — testable, correct foundations  [DONE]
- `SshClient`: split `PROGRAMS` into `REQUIRED_PROGRAMS` (`ssh`, `ssh-keygen`) and
  `OPTIONAL_PROGRAMS` (`sftp`, `scp`); optional binaries extract best-effort and can never
  fail `ensureInstalled()`. Install stamp bumped to `<arch>:<CLIENT_VERSION>` so upgrades
  re-extract. `execScript`/`run` take a `redacted` argument.
- New `SshLaunchOptions`: pure arg builder (was `SshManagerActivity.buildOptions`).
  `SshOptionsTest` rewritten to call production code instead of its private replica.
- New `SshKeyStore`: labeled multi-key registry, JSON in the `ssh_servers` prefs, migrating
  legacy `~/.ssh/id_*` files into labelled entries on first load.
  Fixes `SshClient.privateKeys()` offering `known_hosts` as a deletable key.

### Phase 1 — key management UI  [DONE]
- New `SshKeysActivity`: one card per key (label, type, created, used-by-N) with per-card
  Copy public key / Copy path / Rename / Delete. Replaces the flat `setItems` dialog.
- Generate dialog asks for label + key type (ed25519/ecdsa/rsa-4096) + optional passphrase.
- `SshStore.Server` gained `keyId`; per-server key picker; server card shows the key.
- `SshKeyStore.delete` removes both halves of the pair, drops the entry and clears `keyId`
  on every server that referenced it, so no server points at a purged file.
- `backup_rules.xml`: `ssh-rootfs/root/.ssh/` added to device-transfer only, not cloud-backup.

### Phase 2 — SSH sessions as first-class sessions  [DONE]
- New `SessionDescriptor` (sealed `Local`/`Ssh`) gives each session a durable identity.
- New `SessionStore` persists the open-session list; cleared whenever the last session dies.
- `TerminalViewModel` keeps a descriptor per session and generalizes `removeSessions(predicate)`.
- `TerminalActivity`: single `createSession(SessionRequest)` replaces the SSH/distro fork.
  Launcher scripts are now per-session (`launch-<id>.sh`) — the old fixed name let a second
  concurrent session overwrite the script the first was still reading. The new-session picker
  lists distros *and* servers. Picker key chooser. Guarded distro-only work that leaked into
  SSH launches; fixed export filename, remote path-tap, paste/snippet split-focus bugs,
  split-pane theme repaint, split-pane IME.
- `TerminalService` notification title reflects the current session instead of the first distro.
- Deleting a server kills its running sessions. Sessions restore on cold start.

### Phase 3 — SFTP client  [DONE]
- **SFTP is spoken in-app**, in `util/sftp/`, over `ssh -s user@host sftp` using the bundled
  `ssh`. No `sftp`/`scp` binaries: they are separate executables and cannot be extracted from
  `ssh`, and linking one needs a full OpenSSL for each ABI (`libssh.a` pulls in kex, so a
  hash-only crypto shim is not a shortcut). `build-openssh.sh` therefore still ships only
  `ssh` and `ssh-keygen`, and the APK does not grow.
- `ProotLaunch.writeLauncher(mergeStderr = false)` for this path: proot merges stderr into
  stdout by default, and one host-key warning inside a packet desynchronises the stream for
  the rest of the session.
- `SftpSession` reads through a polling deadline rather than a blocking read, because a
  process pipe has no read timeout and a wedged server would otherwise hang the transfer
  thread forever.
- Progress is reported in **bytes**, not percentages, because the client does the chunking.
- **The `-s` is what makes it a subsystem.** Without it, the trailing `sftp` is a program to
  *run* on the remote host — and the program called `sftp` there is the client, which prints
  its own usage and exits. The trace then shows a clean authentication and a connection
  closed for no visible reason, because the missing flag names nothing that appears in it.
- **A failure report waits briefly for stderr.** stderr is drained on its own thread, so a
  handshake can give up and describe the failure before a single word of ssh's explanation
  has been appended. Read off an empty buffer, every such failure reads as a stopped
  connection whatever the server actually said — which is where the search for the `-s`
  went. `Diagnostics.tailAwaitingFirstLine` waits at most 400ms, and only on the failure
  path.
- New `ui/filelist` package: `FileEntry`, `FileSource`, `LocalDistroSource`, `SftpSource`,
  `BaseFileBrowserActivity`. `FileBrowserActivity` became a thin local implementation and
  `SftpBrowserActivity` a thin remote one.
- Device-side file picking via SAF, so transfers need no storage permission.
- **Nothing about a source's `home` may be read on the main thread.** For the remote
  source it costs an SSH connection, so the browser resolves it on its worker; doing it
  in `onCreate` was a `NetworkOnMainThreadException` and an instant crash.
- **Both key kinds must work.** An unprotected key keeps the plain `BatchMode` path with
  no askpass and no environment at all. A passphrase-protected key is unlocked through
  `SSH_ASKPASS` + `SSH_ASKPASS_REQUIRE=force` (`util/sftp/SshAskpass.kt`): the app asks
  once, keeps the phrase in memory for the process, and `BatchMode` is dropped so ssh is
  allowed to use the helper. Asking ssh's own askpass question was the industry-standard
  answer; the alternative is a "Permission denied (publickey)" for a key the server has
  already accepted, which reads as the server refusing it.
- `SSH_ASKPASS` hands ssh one phrase with no way to say which key it is asking about, so
  a passphrase key is offered **alone** on the command line. Offering several would answer
  one key's question with another's phrase.
- **Ask once per key, then stop.** Every listing and every transfer is its own
  connection, so a rule that re-asks about a key on each one is nagging for ever: the
  user dismisses the dialog and the next tap brings it straight back. `SshAskpass`
  holds phrases *and* declines, and a key is only asked about when it has neither.
  Prompts are serialised, because a listing still running while a transfer starts
  would otherwise stack two dialogs and the answer to the second goes into the first.
- **The browser keeps one connection open.** `SftpSource` holds the `SftpClient` for
  its whole life rather than dialling per operation, which is both the latency the
  user asked for and what stops the passphrase question being asked five times. A
  dropped connection is retried once on a fresh one, because an idle timeout is
  indistinguishable from a bad path otherwise.
- **A dialog answers later; wait for the dismissal, not for the showing.** `show()`
  returns immediately, so reading the result straight after it reads `null` and the
  connection retries with no passphrase — while the user watches the same refusal
  again after having answered the dialog. SFTP showed this as an error appearing
  *underneath* a still-open prompt. `SshKeyUnlock.AnswerLatch` exists so that rule is
  one tested object: the wait blocks, is bounded, and a dismissal by tap-outside or
  back press releases it rather than hanging a transfer thread.
- **One key per connection, tried in turn.** `SSH_ASKPASS` supplies a phrase with no
  way to say which key it is for, so offering several keys in one command and asking
  when one is refused cannot work. `SshKeyAuthenticator` therefore tries candidates one
  at a time and asks about the key actually in use — which is what an agent does. Every
  candidate is kept whatever its passphrase: narrowing the list to one key when another
  is encrypted makes the encrypted key decide which servers the whole device can reach.
- **Unbound servers try the newest key first.** Store order is creation order, so taking
  the list as it comes means the *oldest* key on the device always wins and a key made
  for a server being added right now is never offered. Bound means the user's explicit
  choice and is used alone; ties break on file name so the order is reproducible.
- **Never ask up front; ask when a connection is refused.** A passphrase-protected key and
  a key the server has no record of both produce `Permission denied (publickey)` after
  `Server accepts key`, so the recorded `encrypted` flag is a hint and nothing is gated on
  it. The flag was also unwritable — added to `Entry` and to the reader, not the writer —
  so *every* protected key loaded as unprotected. `SshKeyStore.encode`/`decode` are now
  pure functions round-tripped by a test, because a missing writer field produces no error
  anywhere, only a key the server accepted reported as refused.
- **A secret travels in `ProcessBuilder.environment()`, never in the command.** The command
  is written into a launcher script in app storage and logged on the way past, so an inline
  `VAR=value ssh ...` prefix puts the passphrase on disk in the one place it was promised not
  to be. As an inline prefix it also has to be quoted, and the prefix was once applied to
  *every* argument, which made `ssh` see the assignments as its own arguments.
- `SshAskpass.environmentPrefix` is tested by running it through a real `/bin/sh` and
  reading the variable back in a child process. String comparison proved nothing: the
  prefix was once missing a space, which glued `SSH_ASKPASS` to the next assignment and
  then to the command name, so ssh looked for a helper at a nonsense path.
- `SftpBrowserActivity` must never reject a server for a reason that is not in the
  saved list. An earlier version tested an Intent extra the app never sent, so every
  real server reported "no longer saved".
- An SFTP connection's failure mode is reported from ssh's own stderr
  (`SftpClient.OpenResult`), read *before* the handshake. End of stream is a flag on a
  reader thread, not a null queue element — `LinkedBlockingQueue` rejects nulls, and
  polling `available()` cannot tell a closed pipe from a quiet one.
- Key choice is never a per-server question. `SshKeyStore.identitiesFor` returns the
  server's bound key, or *every* key on the device when none is bound — the labelled
  filenames (`id_<type>_<label>`) are not the names ssh would find by itself. The picker
  asks only which server, and shows "there are no SSH servers" when there are none.
- The SFTP stream has no terminal, so it cannot prompt for a password. A device with no
  key at all cannot transfer, and that is said once, up front.

### Phase 4 — tests  [DONE] / docs  [deferred at the user's request]
- Unit tests: key store naming, `SshLaunchOptions`, `SessionDescriptor` JSON round trip,
  `FileEntry` path arithmetic, and 24 SFTP protocol tests driving `SftpSession` against
  `FakeSftpServer` over pipes. 108 tests, all passing. `org.json` is a test-only dependency
  because the android.jar stub throws.
- Two bugs the protocol tests caught that no amount of reading would have:
  the attributes writer emitted the extended-pair count unconditionally (the spec only
  includes it when the EXTENDED flag is set), which shifted the stream by four bytes on
  every attribute block; and `stat`/`open` discarded the server's status, so a permission
  failure was reported as "no such file".
- README/CHANGELOG deliberately **not** touched: the user wants the changes verified on a
  real device first.

## Toolchain left on this machine (do not delete)

- `/opt/android-sdk` — platform 37.0, build-tools 37.0.0, platform-tools. Its x86-64
  binaries (`aapt2`, `zipalign`, `aapt`, `aidl`) are wrapped shell scripts that exec
  `qemu-x86_64`, because the host is arm64 and they are not.
- `/opt/ndk/android-ndk-r27c` — NDK r27c, needed by `native/build-openssh.sh --ndk`.
- `/tmp/redterm-openssh` — OpenSSL 3.5.0 and OpenSSH 9.9p2 sources plus a partial
  OpenSSL build. `build-openssh.sh` stamps and reuses these, so a rebuild that only
  relinks does not pay for OpenSSL again. Set `TMPDIR` to move it somewhere durable.
- `~/.gradle` — dependency and transform cache.

## The terminal is themed too, not just the chrome

- **A theme's colours reach the terminal through its palette, not its background.** A
  session draws its text with the 259-entry palette inside the terminal library; the
  view's background is a different thing entirely. Setting the background alone gave a
  light theme a light backdrop under text that was still light — a blank-looking
  terminal — and left every other theme on the library's default palette.
- `TerminalPalette.build(foreground, background)` produces that palette: theme
  foreground, background and cursor, the 16 basic colours with their hues intact
  (colour *means* something in a terminal) except black and white, which follow the
  theme or they vanish, and the 6x6x6 cube and greys interpolated between the theme's
  two foregrounds.
- Applied on every attach and on every theme switch, to **all** terminal views — the
  split panes are real TerminalViews with their own palettes, so leaving them out meant
  half the screen kept the old colours.
- `mEmulator` is package-private on the session and public on the view, so the palette
  has to be applied through the view. The session's emulator cannot be reached at all.
- **Adding a theme now needs no new code.** Colours are read from theme attributes, so
  a theme that declares `terminalBg`, `terminalText` and `colorPrimary` themes the whole
  app including the terminal.

## A shell file is run, not read

- **Quote `%` in anything written into a shell.** Bash does not treat a leading `%` as an
  ordinary character: `%1`, `%?` and `%T` are *job specifiers*, and an unquoted one is
  executed as `fg`. `HISTFMT=%F %T` — one assignment and one word, quotes dropped in a
  refactor — therefore made every login print `bash: fg: no job control`, which reads
  exactly like a proot limitation and was nothing of the kind. It was introduced here.
- **The generated `.bashrc` is sourced by a real bash in `ShellConfigSourcingTest`.**
  Every text assertion about it passed with the bug in place; only running the file shows
  the output. Same for the bundled `.bashrc` templates, which are full of `%`.
- A marker (`# managed by RedTerm`) says a file is ours, so a fix reaches installs that
  already have the file. Written only when missing, a corrected line never arrives; written
  unconditionally, a hand-written `.bashrc` is destroyed.

## A backup decides its own contents

- **The archiver on a device is toybox, and toybox cannot store a unix socket.** It prints
  `tar: unknown file type '140000'` — `0140000` is `S_IFSOCK`, masked out of the mode —
  sets a non-zero exit and abandons the *whole* archive. One socket in `/run` fails a
  backup, and it names no file, so the message points at nothing. `RootfsArchive` walks
  the tree first and hands tar an explicit list of what to include, with
  `--no-recursion` so it descends into nothing that is not on it.
- **`--exclude` is not a way to say what not to archive.** How a given tar matches a
  pattern is its own business, and it fails silently — either the entry is archived
  anyway or a real path is dropped. An explicit list cannot be misread.
- **A non-empty archive is not a usable one.** A disk that fills part way through leaves
  something that opens and even lists. Both directions now list the archive first and
  check the count, so a truncated one is a refusal at the point where refusing is free.
- **`java.nio.file` is API 26 and this app supports 24.** `Files.readAttributes`,
  `newDirectoryStream`, `isReadable` and `Path.relativize` all need desugaring. `File`
  alone is API 1 and costs nothing. Do not reach for the tidier tool in one place.
- **A count check cannot catch a plan that is consistently wrong.** `listed == entries`
  passed while every entry was archived as a symlink, because both numbers were the same
  wrong number. What gave it away was 0 KB in a few milliseconds for a multi-gigabyte
  distro. Verify against the tree's real size, not only its shape.
- **The app is not root, and a rootfs is full of files only root can read.** A distro image
  ships `/etc/shadow`, `/etc/gshadow`, `/etc/sudoers` and — Fedora, several hundred — mode
  000. Inside a session proot fakes root so they are readable; outside it `access(R_OK)`
  fails and `tar` cannot read them either, so they never reached the archive. A backup
  missing `/etc/shadow` reports success and restores into a distro where login cannot
  work. `RootfsArchive.makeReadable` grants the owner the bits it is missing, before the
  tree is planned, which is the fix proot-distro applies for the same reason.
- **Never fail a backup over entries it cannot include — report them instead.** A refusal
  is the wrong trade: it throws away a multi-gigabyte install over a handful of entries,
  and reinstalling Arch takes hours. The omissions travel with the result and are shown on
  the line the user is reading, so a backup that quietly lacks files cannot happen
  quietly. The first version of this refused outright and cost one of those reinstalls.
- **A dangling symlink reports as nothing at all.** `getCanonicalPath` cannot resolve one,
  so it returns the path unchanged and the entry looks like not-a-link, not-a-file,
  not-a-directory and unreadable — five signals that all mean "it's a link" once the link
  is broken. `File.exists()` is the reliable one: an entry that came back from a directory
  listing and cannot be stat is a dangling link and nothing else. Arch is full of them
  (`/var/lock` -> `../run/lock`, every `libfoo.so` -> `libfoo.so.1.6.0`), and archiving one
  verbatim is exactly right, since a link has no content to lose.
- **Repairing a locked directory needs the read bit as well as the execute bit.** The
  execute bit gets you in; only the read bit lists what is there, and a directory given
  just the one is still invisible. Adding only execute produces the same nothing as not
  repairing it.
- **Restore needs the space check backup has always had.** The staging directory is on the
  app's own storage, not the shared volume the archive is on, and it holds the *expanded*
  distro — two to three gigabytes for Arch — while the old install is still there. Backup
  has always refused a doomed write; restore did not, so a full disk produced a wall of
  tar errors. A staging tree that merely *looks* like a distro is not a whole one: the
  archive's member count is known before extraction, and far fewer files afterwards means
  extraction was cut short — refused rather than swapped in.
- **A rootfs is full of real hard links, and `link()` fails on this filesystem.** Arch's
  `/etc/ca-certificates/extracted/cadir` is several hundred of them; `tar` records all but
  the first as type `1` and restores them with `link()`, which gives EPERM and takes the
  whole restore down. It is not a distro-specific bug and Void only appears safe because it
  has none there — any distro can hit it. Hard links are materialised as **independent
  copies** by `TarExtractor`, which is also what proot wants: it emulates them with
  link2symlink, so a real hard link would alias inodes the guest treats as separate.
- **A restore must apply the mode and the mtime, not just the content.**
  `FileOutputStream` creates a file `0644` whatever the archive recorded, so a restore that
  writes content and stops leaves an entire rootfs in which nothing can be executed —
  reported as `/bin/bash: Permission denied`, which is only the first thing the startup
  script tries to run. Directory modes are applied **last**, deepest first: applied as each
  directory is created, one recorded read-only refuses the rest of its own contents.
- **`java.io.File` cannot set group bits.** `setReadable(bit, false)` addresses *others*, not
  the owner, and there is no group — so a rootfs's group permissions need `Os.chmod`. The
  same call with `false` also clears the owner's bit, which is how a test that meant to make
  a directory `0700` ended up with `---------`.
- **A tar record is 512-byte aligned, and a payload that has been read must only have its
  *padding* skipped.** Skipping the member as well lands past the next header, so every
  member after the first regular file reads as garbage — which looks like files simply not
  arriving. `TarExtractorTest` packs with real tar and extracts back, which is the only way
  to see that.
- **A carried-over header field must be cleared in the branch that uses it.** `?:` evaluates
  only one side, so clearing a pending long name with `also` on the fallback never ran, and
  one long path then leaked into every member after it.
- **Never leave a child's stderr undrained.** A pipe holds ~64 KiB, so a writer that
  fills it blocks while its reader waits for it to exit — `tar` looks hung and says
  nothing, because it is waiting for us to read a pipe we never opened.
- **Deciding whether an entry is a symlink means resolving the parent, then the child.**
  `child.canonicalPath != child.absolutePath` asks whether the whole path resolves
  elsewhere, so a symlink *above* the entry — and on Android `/data/data` is one, pointing
  at `/data/user/0` — makes every child look like a link. Nothing is then walked, and the
  archive is a tree of links. `RootfsArchiveTest` builds a rootfs behind a symlink for
  exactly this reason.
- The walk is a tested object, not a side effect of the backup: which entries are
  representable, which are skipped and why, parents before children, symlinks never
  followed, and the exact tar command run end to end against a real tar. `RootfsArchiveTest`
  builds a tree containing a fifo for the same reason the bug existed — a test that made
  an ordinary *file* at the socket's path would have passed while proving nothing.

## One permission gate, and the terminal is the model

- **All-files access is asked for at terminal startup when it is not held**, and that single
  gate is what every other flow relies on. Restore appearing to need the grant while a
  backup moments earlier did not was this rule being re-invented per flow — and the fix is
  not a cleverer per-flow check, it is the same check everywhere. A flow that quietly
  proceeds because it happens to be able to is a flow whose behaviour depends on what the
  filesystem allowed that day.
- Backups live in `/sdcard/RedTerm` deliberately, so they survive uninstall/reinstall. That
  grant is per-install, so after a reinstall the archive listing is empty until it is
  re-granted; `BackupManagerActivity` says so rather than showing an empty list.

## A distro badge never blocks and never fails

- Three sources in order — `assets/distro-icons/<name>.png`, a logo downloaded on an earlier
  visit, and a lettered badge — and **the letters are always shown first**. A card is never
  blank, never waits on the network, and does not change size when a logo arrives: the
  letters and the image share one fixed-size frame and swap visibility.
- A download is refused unless the response is a real image, checked by magic number. A host
  answering an image request with HTML must not be able to fill the cache with something
  that is not an icon.
- **One constant, not eleven URLs.** `DistroIconStore.BASE_URL` is empty by default, so
  nothing is fetched and every card is lettered. Point it at the project's own hosting and
  every logo appears; there is no list of third-party paths to go stale, and no guessed URL
  in the source — every candidate tried returned 404, which would have produced letters
  everywhere and nothing saying why.
- The badge goes wherever a distro's **name** goes, and the name comes from the registry
  rather than from capitalising the install key — that rendered "Almalinux" for `almalinux`
  and "Opensuse" for `opensuse`. `DistroBadge.create` takes the key, because most callers
  only ever have the key, and falls back to a capitalised key for a distro the registry has
  since dropped.
- **A row that rebuilds must not re-decode its icons.** Every card and list row asks for
  the same handful of logos, and rows are rebuilt on every resume, so decoding from the
  asset each time — plus a `getPackageInfo` binder call for the version stamp — was
  main-thread work repeated forever. `DistroIconStore.iconOrNull` resolves memory, then
  bundle, then download in one lookup, and `BoundedIconCache` keeps the result.
- **`android.util.LruCache` is not usable in this project's unit tests** — it is one of the
  framework classes stubbed off-device, so a cache built on it would have its eviction and
  sizing rules permanently unverified. `BoundedIconCache` is the same algorithm in testable
  lines, bounded by *bytes* rather than entries because the entries differ in size.
- The icon's foreground colour is chosen by measured contrast, not by a threshold. Arch's
  blue sits just above a 0.5 luminance cut while still favouring dark ink, and a threshold
  put its badge at 3.43:1.

## Known follow-ups (deliberately out of scope)

- Split view is still exactly two panes.
- Remote recursive search is one level only: each level is a round trip.
- Downloading a whole remote folder is not offered; the files inside it are.
- Cross-compiling here is not viable: the NDK ships x86-64 host tools and this box is
  arm64, so emulating them measured ~60x slower than native. One command in parallel with
  anything else is enough to make the VM unusable — run builds one at a time, `nice`d.