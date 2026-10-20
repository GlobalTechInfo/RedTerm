# RedTerm — agent notes

## The warning gate, stated first because it was missed once

**The build must be clean and it must be checked.** Zero compiler errors, zero
deprecation warnings, zero other warnings — no more than the fixed set of three
pre-existing `Aligned16KB` lint warnings from the upstream termux `.so` files. No
new suppressions (`@Suppress`, `@SuppressLint`, empty catch) are allowed, ever.
The gate is reading the output, not the exit code: Gradle exits green over a wall
of warnings unless someone reads the `w:` lines of `compileDebugKotlin` and the
lint report line by line. Fix at the source or remove the code; a deprecated call
is a call the platform has already withdrawn its promise for.

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

## The build host, and what it can and cannot do

**This machine is Ubuntu 26.04.1 LTS on aarch64.** It is not a proot Ubuntu session and is not
cross-emulating anything for the app's own build — the JDK, the Android SDK and the NDK are all
native arm64 and all present, so `test`, `lint` and `assembleDebug` **do** run here and the gate
can actually be met before anything is handed over. Verify rather than assume that still holds;
it did not earlier in this project's life, and an agent that trusts a stale note here will tell
you a build is impossible when it is not.

What is here:

- **JDK 17 (arm64)** — `java`, `/usr/lib/jvm/java-17-openjdk-arm64`. Gradle finds it.
- **`/opt/android-sdk`** — platform 37.0, build-tools 37.0.0, platform-tools.
- **`/opt/ndk/android-ndk-r27c`** — for `native/build-openssh.sh --ndk`.
- **`~/.gradle`** — dependency and transform cache. Deleting it costs a full re-resolve.
- **`skopeo`, `unshare`, `chroot`, `curl`, `xz`, `jq`, `sudo`** — enough to build rootfs images.
  There is **no Docker daemon**; `scripts/oci-unpack.sh` uses skopeo instead, so that is not a gap.

Constraints that are not obvious and will waste an hour if rediscovered:

- **One CPU core, 5.8 GiB RAM, no swap.** Run one heavy build at a time. A rootfs image build and
  a Gradle run in parallel star each other: the ARMtix build was killed once because a Kotlin
  daemon held 2 GiB while `xz` compressed a 1.5 GiB tree. Background the long one and wait.
- **`xz` on a 1.5 GiB tree takes well over an hour.** A build killed at a 30- or 90-minute cap
  leaves a truncated `.tar.xz.tmp` and an unchanged final file, which looks like "still building".
  Use `setsid nohup` and poll the `.tmp` size rather than holding one tool call open.
- **`chroot` and `mount` need `sudo`.** The shell has `CapEff: 0`, so an unprivileged `chroot`
  fails with `Operation not permitted` even though `sudo -n` succeeds.
- **`/tmp` is a 2.9 GiB tmpfs.** Extract at most one or two images at a time.

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
  goes through `ToolOutputDialog` — selectable, scrollable, monospaced. **Selection
  needs the movement method `setTextIsSelectable` installs**: setting
  `ScrollingMovementMethod` after it is what broke long-press selection (and the
  NestedScrollView scrolls anyway), so the view sets selectable last and no other
  movement method; and a **Copy button** sits beside selectable text, because one
  tap that always works beats a long-press that usually does. A restore failure
  also goes to `AppLog`, because `android.util.Log` is invisible in Diagnostics and the
  dialog is the only copy that gets dismissed.
- **A success does not want the diagnosis.** The connection test always ran `ssh -v`
  and printed the whole trace whatever the outcome, so a success card was a wall of
  key-exchange debug lines — which reads like something went wrong. The trace is the
  failure's report; a success shows the method, the key used and the server's version
  banner, and nothing else.
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
- **A SAF picker's MIME filter is a promise the provider did not make.** Providers report
  the same font as `font/ttf`, `application/x-font-ttf` or plain `application/octet-stream`;
  `font/*` matched none of them, and a picker that filtered by it opened every folder empty
  and hid exactly the files the user was reaching for. Filter nothing, and validate what
  was picked (`util/FontImport.kt`) — where the display name via `OpenableColumns` is on
  record, because a document URI's `lastPathSegment` is a provider id on anything but the
  common local storage provider. The mistake also had a fallback that looked like a fix and
  could never run: it lived inside the `catch` of `launch()`, which throws only when there
  is no picker at all, not when the picker opens fine and shows nothing.

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
- **One question per connection attempt, and it must return.** A prompt dismissed
  once used to be recorded in process-wide state and every later test and transfer
  went on failing without ever asking again — the opposite of the anti-nagging it
  was built for. A dismissal now lasts exactly as long as the connection attempt it
  interrupted (`SshKeyAuthenticator` asks at most once per attempt): the next one
  asks again, because a question that never returns cannot be answered. A phrase
  held in memory is also never proof the next attempt cannot need a fresh one — a
  held value can itself be why the attempt refused. Prompts stay serialised, because
  a listing still running while a transfer starts would otherwise stack two dialogs
  and the answer to the second goes into the first.
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
- **Never ask up front; ask when a connection is refused — the key file settles the
  question.** A passphrase-protected key and a key the server has no record of both
  produce `Permission denied (publickey)` after `Server accepts key`, so the recorded
  `encrypted` flag is a hint and nothing is gated on it; the flag was also once
  unwritable, so *every* protected key loaded as unprotected. But the two cases are
  not indistinguishable to the key file itself: `ssh-keygen -y -P "" -f <key>` reads
  an open key with exit 0 and refuses an encrypted one, over no network at all
  (`SshKeyImporter.readsWithoutPassphrase`). A refused attempt runs that probe before
  any question is asked — an open key can never make a passphrase work, so asking
  about one was the app reporting "the server does not know this key" in the words of
  a passphrase dialog, which is how a user who generated a key with no passphrase was
  asked for one. An encrypted key is asked about freed from any held or declined
  state, because a held phrase may itself be why the attempt refused.
- **"No prompt needed" and "prompt dismissed" are different outcomes, and only one
  of them spends the question.** The loop's one-shot asking was implemented as "the
  first refusal calls ask and marks the question spent", which made an *open* key's
  refusal — where the asker answers `NotNeeded` without showing anything — consume
  the only prompt the attempt had, so an encrypted key further down the list was
  never asked and a server that accepts it rejected the connection. `ask` now
  returns `Prompt.NotNeeded` or `Prompt.Answered`, and only `Answered` sets the
  flag: an opportunity is spent by a question actually put, not by a refusal that
  came before it.
- **A lock held for a transfer's whole duration must not be read from the main
  thread.** `SftpSource.hasConnection` took `connectionLock`, the same lock
  `withConnection` holds through the ssh dial and every SFTP round trip, so a tap
  starting a load could freeze the UI behind a running transfer. `connection` is
  now `@Volatile` and the getter takes no lock — and no second hand-maintained
  flag either, which is a copied value waiting to drift; the advisory read is
  allowed to be stale, because the next call dials if it has to.
- `SshKeyStore.encode`/`decode` are pure functions round-tripped by a test, because
  a missing writer field produces no error anywhere, only a key the server accepted
  reported as refused.
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

### Phase 4 — tests  [DONE] / docs  [DONE, deferred then written up]
- Unit tests: key store naming, `SshLaunchOptions`, `SessionDescriptor` JSON round trip,
  `FileEntry` path arithmetic, and 24 SFTP protocol tests driving `SftpSession` against
  `FakeSftpServer` over pipes. `org.json` is a test-only dependency because the android.jar
  stub throws.
- Two bugs the protocol tests caught that no amount of reading would have:
  the attributes writer emitted the extended-pair count unconditionally (the spec only
  includes it when the EXTENDED flag is set), which shifted the stream by four bytes on
  every attribute block; and `stat`/`open` discarded the server's status, so a permission
  failure was reported as "no such file".
- README/CHANGELOG deliberately **not** touched: the user wants the changes verified on a
  real device first.

## Toolchain left on this machine (do not delete)

- `/opt/android-sdk` — platform 37.0, build-tools 37.0.0, platform-tools.
- `/opt/ndk/android-ndk-r27c` — NDK r27c, needed by `native/build-openssh.sh --ndk`.
- `/tmp/redterm-openssh` — OpenSSL 3.5.0 and OpenSSH 9.9p2 sources plus a partial
  OpenSSL build. `build-openssh.sh` stamps and reuses these, so a rebuild that only
  relinks does not pay for OpenSSL again. Set `TMPDIR` to move it somewhere durable.
- `~/.gradle` — dependency and transform cache.
- `/home/ubuntu/rootfs-images` — the image repository. `main` is what CI builds from, and a
  push to it runs the GitHub Actions workflow. See the warning below before pushing anything.

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
- **Hard links are type `1` and must become independent copies; a symlink is not one.**
  This note previously claimed Arch's `/etc/ca-certificates/extracted/cadir` holds several
  hundred hard links. **It does not.** Measured against the published image:
  `python3 -c "import tarfile; ..."` over `arch-aarch64-*.tar.xz` gives the whole archive as
  13107 regular files, 1210 symlinks, 822 directories and **zero type `1` entries**; `cadir`
  itself is 121 regular files, 242 symlinks (`002c0b4f.0 -> GlobalSign_Root_R46.pem`) and
  one directory. `tar -tv` shows a symlink as `l` and a hard link as `h`, and `l` is easy to
  read as "link" — which is how several hundred symlinks became several hundred hard links
  in this file and then in a commit message. `cadir`'s **directory** mode is 0555, and that
  is the part that mattered (see `TreeRemoval`).
  The rule itself stands: where a type `1` entry *does* appear — any distro can hit it, and
  it is not Arch-specific — restoring it with `link()` gives EPERM and takes the restore
  down. Hard links are materialised as **independent copies** by `TarExtractor` and by
  `RootfsInstallExtractor`, which is also what proot wants: it emulates them with
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

## A rootfs install must keep the modes the archive recorded

Full evidence, measurements and reproduction steps are in
[`docs/proot-distro-reference.md`](docs/proot-distro-reference.md). Read that before
changing anything about distro images or extraction.

- **A mode the archive records read-only is a trap on the *next* install.**
  `/etc/ca-certificates/extracted/cadir` is `0555`, and it is correct. The first install
  writes that mode at the end of extraction, so the second install cannot clear it:
  unlinking a child needs write access to its parent. `deleteRecursively()` returns
  false, **the return value was ignored**, extraction continued into the old tree, and
  every write into the surviving `0444` files failed:

      .../cadir/ACCVRAIZ1.pem: open failed: EACCES (Permission denied)

  That is why install #1 worked, install #2 did not, and why the obvious response —
  reinstall — is what triggers it. `TreeRemoval` makes each directory owner-writable
  **before** descending into it (afterwards the directory can be chmod-ed but never
  deleted, because it is not empty), and *reports* whether the removal happened so the
  install refuses rather than extracting into a tree it could not clear. Files are never
  the obstacle: permission to unlink comes from the parent.
  Verified by extracting the real published `cadir` subtree twice: pass two reproduces
  the EACCES verbatim, pass three (with `TreeRemoval` first) writes all 363 members.
- **Two extractors existed and only one was correct.** Restore has always gone through
  `TarExtractor`; install had its own loop inside `DistroInstaller`. The install one
  `chmod`'d every directory to 0755 as it created it and reduced every file to
  "executable or not", reading `entry.mode` and throwing it away. That produced, on every
  pacman transaction: `warning: directory permissions differ on /tmp/ filesystem: 755
  package: 1777`. The published images were correct the whole time — no image-side change
  could ever have fixed it. Both now share `RootfsInstallExtractor`.
- **A mode that is uniform is a mode nobody checked.** The damage was identical on every
  entry, so a tree that is uniformly wrong passes any test that only asks "is it
  readable". `RootfsInstallExtractorTest` packs a tree with real `tar` and asserts 1777,
  2755, 750, 555 and 700 individually; four of its six tests fail against the old code,
  which is the only reason to trust it.
- **Directory modes are applied last, deepest first.** A directory the archive records as
  0555 cannot have children created inside it, so setting its mode on arrival strands
  everything below it. proot-distro's install does the same with
  `--delay-directory-restore` (`proot-distro.sh:535`). Ordering by path length is the proxy;
  `TarExtractor` orders the same way.
- **The mask is `0xFFF`**, not `0x1FF`: that keeps setuid, setgid and sticky and drops only
  the file-type nibble, which is not a `chmod` argument. `0x1FF` silently discards setgid
  and sticky — which is exactly how `/tmp` stops being sticky.
- **There is no post-extract permission repair any more.** The repair that walked the tree
  and `chmod`'d again could only ever agree with the flattening the extractor had already
  done; it had no other mode to work from. A repair step that exists only to undo this
  codebase's own bug is a symptom, not a fix.
- **`find -perm -400` is the owner EXECUTE bit, not setuid.** `find` parses the mode as
  octal; `0400` is bit 8 and setuid is `04000`. Measured: it matches every traversable
  directory (22197 of 22197 under `/usr`) and zero setuid directories. proot-distro's
  "repair unreadable directories" line therefore chmods every directory to 755 and repairs
  nothing — and our `bootstrap-rootfs.sh` comment once repeated the wrong reason.

## openSUSE was never misconfigured, and testing that is what proved it

- **`zypper refresh` succeeds on our published image exactly as shipped**, run natively
  in a chroot, as it does on proot-distro's — whose `repos.d` files are byte-identical to
  ours. It builds `/var/cache/zypp/solv/openSUSE:repo-oss/solv`, the very path reported as
  impossible, and `zypper search` then answers from it.
- **Every cause that was "fixed" is disproven**: a colon in a repository alias is fine;
  `openSUSE.service` populates the repositories rather than breaking them, and
  `Refreshing service 'openSUSE'.` is it working; libzypp creates the per-repository cache
  directory itself, so an absent cache tree is not a fault; `RepoManager.db` is rebuilt and
  caused nothing. `Curl error (28)` is a network timeout — both the http and https Leap
  16.0 aarch64 baseurls answer in under a second.
- **`opensuseClean` is now one command**: make `/var/cache/zypp` writable. That is the only
  part that survives, and it survives on the *mechanism* — a session runs as the app's uid
  while the image built the cache as root — not on a reproduction, because zypper refuses to
  run without uid 0 off-device and the app depends on proot to fake it. Say so in the
  comment rather than implying it is proven.
- **Rewriting configuration you could not test destroys working configuration.** The
  alias de-colonning, the `service=` removal and the service deletion each cost the user
  repositories they could install from and fixed nothing, across several releases.
- proot-distro's whole openSUSE recipe is 3393 bytes and touches none of this. When the
  reference has nothing to say about a failure, the only thing it offers is *"this works
  there"* — which means download both and compare, not reason harder.

## An image is verified before it is published, not after

- **Build one recipe, then read the result out of the tarball.** `tar -tvf` on the `.tar.xz`
  answers mode questions that no amount of reading the recipe will. What a correct ARMtix image
  looks like, checked directly:
  `DisableSandbox` / `HookDir` / `NoExtract` / `DownloadUser` present **exactly once** each,
  `etc/pacman.d/hooks/` present and empty, `ID=arch` in `/etc/os-release`.
- **The mode a tarball *stores* is the mode that matters.** Extracting as a non-root user
  applies your umask, so a `drwxrwxrwx` entry reads back as `drwxr-xr-x` and looks like the
  build failed to set the bit. `tar -tvf` reads the header without that. That is not a
  hypothetical: it happened here while checking openSUSE's cache.
- **A recipe that applies the same edit twice is a duplicate directive.** `sed '/^\[options\]/a
  DisableSandbox'` appends unconditionally, so applying the block once early and once late puts
  it in the image twice, and pacman reports that on every command. Use the delete-then-insert
  form, as the `HookDir` and `NoExtract` steps already do.
- **Publish with `--clobber` per asset, never by recreating the release.** Builds are
  incremental, so recreating publishes only what happened to be rebuilt and deletes every other
  distribution. A run that built three distros once deleted the other twenty, and the index
  agreed with the loss, so nothing reported it as missing.
- **Merge the index with the published one, never replace it.** A build sees only the recipes it
  ran, so an index generated from local state alone hides everything it did not build. Ubuntu
  has no local tarball and would simply disappear. `scripts/merge-index.py` is what prevents it.

## mosh is being built, and two things were measured rather than designed

- **An Android UDP socket survives a network change by itself.** This is the whole
  question mosh on a phone turns on, and it was measured rather than assumed because
  the assumption was expensive to be wrong about: Android binds a socket to a
  network, so a WiFi to cellular change is expected to destroy it, and mosh's
  roaming only works if the client's socket keeps sending.

  Measured on a device, over six switches in ~50s, with one socket opened before
  the first switch and never closed, never re-pinned and never rebuilt:

      18:40:07.676  probe #152 FAIL  ENETUNREACH
      ...
      18:40:08.191  capsChanged cellular validated
      18:40:08.590  --> RECOVERED on the SAME socket after 4 failure(s),
                     with no intervention

  So there is a **~900ms window** where the socket answers `ENETUNREACH`, and then
  it works again on its own. Five of the six switches were seamless. Re-pinning the
  process with `ConnectivityManager.bindProcessToNetwork` **does not** revive it
  inside that window, which is why the answer is "wait" rather than "re-bind".

  Consequence: the native transport needs no network awareness at all. Nothing
  listens for connectivity changes, and nothing re-creates the socket. A ~1s gap is
  well inside what SSP's RTT-based retransmission already absorbs.

  Three earlier versions of the probe each produced a log that *looked* like
  evidence and was not, and the reasons are worth keeping:
  - **DNS is the wrong probe.** UDP/53 to 8.8.8.8 *and* 1.1.1.1 times out on a
    healthy WiFi, because Android's Private DNS (default since Android 9) resolves
    over DoT on TCP/853 and never answers plain UDP/53 from an app. "No reply" was
    the probe's fault, not the network's.
  - **Android invalidates a Network in place.** The same id lost its `wifi`
    transport and gained `cellular` with no `onAvailable` and no `onLost`. Anything
    keying on Network *identity* sees no change at all. Compare transports.
  - **A switch is a window, not an instant.** Acting inside it measures the window.
    A re-pin and a brand new socket both "failed" there and both worked on the very
    next probe with no intervention. A probe that keeps both sockets open and asks
    only once the network has settled is the only version of this that can be
    believed.
  And one arithmetic detail: when a query falls through from one server to the next,
  the first server's late reply stays in the socket, so the next call consumes it and
  reports **0ms**. Draining before each query is what makes a reported round trip
  mean a round trip.

- **nettle, not OpenSSL, for mosh's AES.** mosh's configure accepts either, and the
  difference is not close. Measured for a single aarch64 ABI:

  | | OpenSSL | nettle |
  |---|---|---|
  | stripped `.so` | 5,351,472 | **978,496** |
  | crypto library | 10.2 MB | **2.15 MB** |
  | crypto build | 7,837s | **1,159s** |

  The reason is symbol count: **OpenSSL contributes 3393 symbols, mosh contributes
  31.** One call to `Crypto::Session::encrypt` drags in the whole of OpenSSL 3.5's
  AES path. With nettle, mosh needs no OpenSSL at all — `configure` reports
  `internal OCB, Nettle AES` — and OpenSSH keeps its own, already built.

- **`libmoshterminal` is not curses-free**, contrary to what an iOS build of mosh
  suggests. `src/terminal/terminaldisplayinit.cc` calls `setupterm`, `tigetflag` and
  `tigetstr` and hard-errors without a curses header. Nothing references
  `TerminalDisplayInit` — it exists for the curses frontend, which
  `--disable-client` does not build — so a **declaration-only** `curses.h` stub
  compiles the object and it is never pulled into the `.so`. If a future change ever
  does reference it, the link fails on an undefined `setupterm` rather than
  silently binding to a terminfo database we do not ship.

- **Three of mosh's, protobuf's and nettle's own build trees try to regenerate their
  autotools output**, because timestamps did not survive packaging:

      make[1]: *** [Makefile:405: Makefile.in] Error 1
      missing: line 81: automake-1.16: command not found
      make[1]: *** [Makefile:1699: aclocal.m4] Error 127

  Same fix `native/build-openssh.sh` already uses: touch `aclocal.m4`, then
  `configure`/`config.h.in`, then every `Makefile.in`, so make considers them
  current. nettle needs `make desdata` first with a **host** compiler — it generates
  `rotors.h`, and built with the cross wrapper it is an aarch64 binary that cannot
  run here — and `stamp-h.in` touched, or `make install` reaches for `autoheader`.

- **Two more bionic gaps, both of the kind `native/ssh_compat.c` already exists
  for.** `nl_langinfo` is declared only under `__ANDROID_API__ >= 26` and the app
  supports 24, so it is neither declared nor present; mosh uses exactly one item,
  `CODESET`, only to rewrite glibc's `ANSI_X3.4-1968`, so a stand-in is honest. The
  force-include has to go on **`CXXFLAGS`**, not just `CFLAGS`, or it compiles the C
  sources and fails the first C++ one, which reads exactly like the shim not working.

- **protobuf needs the *lite* runtime and nothing else.** mosh's three `.proto`
  files are generated `optimize_for = LITE_RUNTIME`. Asking automake for
  `install-libLTLIBRARIES` builds libprotobuf, libprotobuf-lite, **libprotoc and
  libupb** — over an hour of a single-core host for two libraries mosh never links.
  Copy `src/.libs/libprotobuf-lite.a` by hand. Its public headers include `.inc`
  files as well as `.h`, and mosh's configure reports the unhelpful "Could not build
  output generated by protoc" when one is missing.

- **The `.so` size so far is crypto-only** (978,496 bytes) because the probe
  exercised `Crypto::Session` and therefore pulled in neither protobuf nor statesync.
  The real client will be larger. What is proven is that OpenSSL's ~4.4 MB is gone,
  and that the shared object needs only bionic (`libz liblog libdl libm libc`), so
  no extra `.so` ships.

## mosh's clock is frozen on purpose, and that is the entire send path

- **`Network::timestamp()` returns `frozen_timestamp()`, and `frozen_timestamp()` reads a
  millisecond cache exactly once.** It then returns that same value for ever. The cache is
  advanced only by an explicit `freeze_timestamp()` call, and in the whole of mosh 1.4.0
  there are exactly three callers:

      frontend/stmclient.cc:567   freeze_timestamp();
      frontend/mosh-server.cc:141  freeze_timestamp();
      util/select.h:187            freeze_timestamp()

- **Write the client loop without that call and mosh sends your first frame and nothing
  else, for ever.** The initial terminal state goes out, the clock sticks at the first
  timestamp the client ever read, and every timer test stays `now < next_send_time`, so
  `TransportSender::tick()` returns before it ever reaches `send_to_receiver()`. Keystrokes
  queue correctly and visibly — `get_current_state().size()` goes to 20 — the sender is
  asked to tick and declines, and the remote shell never sees them. There is no error, no
  exception and no output: the session is connected, authenticated, drawing a prompt, and
  one-way.

- **Nothing in the trace points at it.** `revents` was 0x1 (data really was arriving),
  the handshake had completed, and the framebuffer was updating. The clock was the only
  thing not moving, and nothing prints it. It was found by patching
  `transportsender-impl.h` to print `now`, `next_send_time` and `next_ack_time`: every
  tick showed the same `now`, and `next_send_time = 18446744073709551615` — `uint64_t(-1)`
  — which is what an empty diff sets it to.

- **It is a test facility, and the name is the warning.** `frozen_timestamp()` exists so
  mosh's own tests are deterministic. Shipping mosh's libraries means shipping a frozen
  clock *and* its driver, and the driver lives in the frontends rather than in the
  library, so nothing tells you one is required.

## mosh needs an executable, because TerminalSession cannot be anything else

- **`TerminalSession` is `final` and its constructor takes a shell *path*.** It builds the
  subprocess itself through `JNI.createSubprocess`; there is no constructor that accepts a
  `Process`, and there is no `JNIShell` in v0.118.3 to subclass. `TerminalSession` is also
  not ours — it is the upstream jitpack library.

- **So a mosh session is a process, and the process is a native client.** The app already
  ships real executables as per-ABI assets (`assets/ssh/<arch>/`) extracted to the rootfs
  and run through proot's `-L`, so mosh follows the same path rather than inventing one:
  link `redterm_mosh_client.cpp` and `mosh_display_compat.cpp` into an executable that
  reads its endpoint from the environment and proxies stdin/stdout. `TerminalSession`
  then needs no change at all, which is the point — the session machinery is not ours to
  modify and must not have to be.

- **`MOSH_KEY` belongs in the environment, not the command line.** The launcher script is
  written to app storage and logged on the way past, so this is the rule from `SshAskpass`
  arriving unchanged. The executable takes the address, port and key from its environment
  and passes the key to the session in memory.

## What mosh-server actually prints, and where the address comes from

- **`mosh-server new` prints `MOSH CONNECT <port> <key>` and nothing else.** It does not
  print `MOSH IP` or `MOSH SSH_CONNECTION`, and `scripts/mosh`'s parser looks for all
  three. Measured against 1.4.0's own binary with and without `SSH_CONNECTION` set: the
  only `printf` of a `MOSH` line is the connect one.

- **`mosh-server` binds `$SSH_CONNECTION`'s server-side field, and that is *not* the
  address the client should send to.** Measured on a real Oracle instance:

      ip -4 addr  ->  127.0.0.1, 10.0.0.79 (enp0s6), 100.68.49.100 (tailscale0)
                      no public IP on any interface; 129.146.25.232 is 1:1 NAT

  Reached over Tailscale, the ssh session arrives on `tailscale0`, so the field is
  `100.68.49.100` and everything works. Reached by public IP, the session arrives on
  `enp0s6` after the NAT, so the field is `10.0.0.79` — `mosh-server` binds it correctly
  and the cloud would forward inbound UDP to it happily, but a client that trusted the
  number sent its packets into the VPC from the internet and every session timed out
  having proved nothing. **This was written down as if it were the right answer before it
  had been measured; it is right for what the server binds and wrong for where the client
  sends.** An earlier note here claimed the opposite with confidence; that claim is what
  cost the whole feature its first working run over a public IP.

- **The destination is the address ssh connected to** — the host the user configured,
  resolved on the device, preferring IPv4. It is always reachable because it is the one
  that just worked. `$SSH_CONNECTION` is kept only for the case where the host cannot be
  resolved on the device at all, which is the only case it is good for.
  `MoshBootstrap.udpDestination` is the rule and it is a pure function with tests.

- **`mosh-server` still has to be started with the connection echoed**, because the
  bootstrap wants to know the server is listening at all, and gets that from
  `MOSH CONNECT`. It is started as
  `/bin/sh -c "echo MOSH SSH_CONNECTION \$SSH_CONNECTION; exec mosh-server new"` in the
  same ssh invocation, so it costs no second connection and no second authentication.

- **`mosh-server` forks and detaches after printing, so ssh exits on its own.** The
  parent exits and the detached child keeps the UDP port bound, which means a
  wait-for-exit call is sufficient and no streaming read is needed. Verified by checking
  the foreground process had exited while the port was still bound.

- **The escaping is three shells deep and two of the three are traps.** `$SSH_CONNECTION`
  must survive the device shell unexpanded and be expanded by the *remote* shell. Double
  quotes with `\$` do that; single quotes do not, because a backslash inside them is
  literal and the remote shell would print the text instead of the value. `exec` matters
  too — without it the wrapper shell lingers as mosh-server's parent and the bootstrap
  appears to hang on a session that is already ready. Both levels were run against the
  real binary rather than reasoned about.

## Display's constructor is the only curses-dependent thing we need, and it is not

- **`Terminal::Display` cannot be replaced.** `new_frame()` is what turns a framebuffer
  diff into the escape sequences the Android emulator understands, and reimplementing it
  means reimplementing wrapping, scrolling regions, wide characters and SGR bookkeeping.

- **`build-mosh.sh` deletes `terminaldisplayinit.o` from `libmoshterminal.a`** because it
  is the one file in `src/terminal` calling `setupterm`/`tigetflag`/`tigetstr`. That was
  sound while nothing referenced it — and `Display::Display(bool)` lives in that same
  file, so the moment `Display` is used the link fails on an undefined constructor. This
  is the documented failure arriving: it was noted that referencing it would break the
  link rather than silently bind to a terminfo database, and it did.

- **Given `use_environment == false` the constructor body is the member initialiser list
  and nothing else.** All the capability discovery is inside `if ( use_environment )`,
  which is exactly what an Android device cannot answer anyway. `mosh_display_compat.cpp`
  supplies it, with `smcup`/`rmcup` null (no alternate screen on a phone), `has_bce` true
  (erases carry their background colour), `has_ech` true and `has_title` true. A `true`
  argument throws rather than guessing, which is what upstream does when the database is
  missing.

## The shim yields the core, and two of its bugs were only findable by running it

- **`redterm-mosh` is host-testable, and that is how two of its three bugs were found.**
  The same sources that go into the `.so` link into an executable, and against a real
  `mosh-server` on this machine it can be driven end to end: start a server, point the
  shim at it, type a command, look for the echo. Both of the following were invisible to
  reading and obvious to running.

- **A give-up timer must count milliseconds, not loop iterations.** The first version
  gave up after 50 idle rounds. mosh's `wait_time()` returns **zero** when its timers are
  due, so each round returned immediately and 50 rounds took milliseconds: the client
  exited with "the connection went away" before the first UDP packet had been read. The
  same class of error as the frozen clock above — an iteration is not a duration.

- **Then it still failed, and the cause is this host, not the code.** The session pumps
  its own network on a worker thread, and the shim's loop polls stdin with whatever
  `wait_time()` said. With a zero timeout that is a spin, and this machine has **one CPU
  core**, so the spinner and the worker split it and the worker — the only thing that can
  bring data in — got almost none. `poll` now has a **20 ms floor** so the loop yields.
  On a multicore phone this would have been intermittent rather than total, which is a
  considerably worse bug to chase.

- **`TerminalView` in v0.118.3 has no column/row getter** (`mEmulator` is package private
  on the session), so the initial `MOSH_COLS`/`MOSH_ROWS` are a seed rather than an
  answer. The shim re-reads the pty on every SIGWINCH and `TerminalSession` sends one as
  soon as the view is laid out, so the first frame is corrected almost immediately.

## Two traps that each looked like something else

- **A compiler wrapper that appends a flag silently overrides the caller.** The `cxx`
  wrapper in `build-mosh.sh` ended `exec "$REALCXX" "$@" ... -Wl,-z,max-page-size=$PAGE_SIZE`,
  so the executable's `4096` was followed by the wrapper's `16384` and lost. Nothing
  failed: the link succeeded, the binary was simply aligned the other way, and the only
  sign was a check comparing against a number the wrapper had just changed. A wrapper that
  adds a flag has to let the caller win, or it is not a default — the same shape as
  `SshAskpass.environmentPrefix` once being applied to every argument.

- **The two ABI vocabularies disagree, and the failure is a missing file.** `assets/` uses
  build-openssh.sh's names (`aarch64`, `armv7a`, `x86_64`, `i686`); `Build.SUPPORTED_ABIS`
  and jniLibs use `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`. Using the ABI name verbatim
  opens `assets/mosh/arm64-v8a/…`, which does not exist — so *every* device would report
  "could not install the mosh client", including ones the binary was built for, and the
  APK would visibly contain the right file. `MoshClient.assetArch` is a pure function with
  a test over every shipped ABI for this reason.

## The mosh toggle is a preference, never a requirement

- **`useMosh` is on both `SshStore.Server` and `SessionDescriptor.Ssh`, written *and*
  read**, with a round-trip test and a hand-written pre-mosh record to prove the reader
  tolerates its absence. A field added to the reader and not the writer has no symptom
  anywhere: the server loads, the toggle renders, and every session quietly opens over ssh.
  That is the same shape of bug as the `encrypted` flag on key entries.

- **Falling back is the design, not an error path.** mosh needs `mosh-server` on the host
  and UDP 60000-61000 open to it; the app can arrange neither and can barely see either. So
  `TerminalActivity` bootstraps on a worker thread and attaches whatever came back, saying
  through `AppLog` which protocol it used and why — a silent fallback is indistinguishable
  from the preference having been ignored.

- **`MOSH_KEY` reaches the client in the process environment**, through the `env` array
  `TerminalSession` passes to the subprocess, and the launcher script contains nothing but
  the binary's path. This is `SshAskpass`'s rule a third time, now in a subsystem that
  has no askpass in it.

## The terminal view hides a null renderer behind a zero width

- **`TerminalView.mRenderer` is written by exactly two methods in the library:
  `setTextSize(int)` and `setTypeface(Typeface)`.** Nothing else — not the constructor,
  not `onSizeChanged`, not `attachSession`. Confirmed from the bytecode of v0.118.3,
  where those are the only two `putfield mRenderer` in the class.

- **`attachSession` calls `updateSize()` unconditionally**, and `updateSize()` guards on
  `width == 0 || height == 0 || mTermSession == null` — **but not on the renderer being
  non-null**. The line after the guard divides the width by `mRenderer.mFontWidth`.

- **So attaching a session before the view has been laid out is safe, and attaching it
  afterwards is not.** During `onCreate` the width is 0, `updateSize()` returns at once,
  and the missing renderer costs nothing. Move the attach past the first layout — which
  is what mosh's bootstrap does, since it takes seconds — and the guard passes, the
  renderer is read, and the app crashes on `mFontWidth`.

- **Two of the three attach paths in `TerminalActivity` had the right order all along**
  (font, then `attachSession`); the fresh-session one did not, and nothing noticed
  because the width was 0 on every launch it had ever done. **The lesson is not "call
  `setTextSize` earlier" — it is that a guard which returns early can hide a missing
  invariant for as long as the early-exit condition is always true, and an always-true
  condition stops being true the moment anything becomes asynchronous.** When making a
  path async, re-check what the synchronous path was accidentally relying on.

## A `when` that returns null for two different things is not a check

- The mosh preference was read as

      when { !server.useMosh -> null; !loaded -> reason; !installed -> reason; else -> null }

  which answers "if mosh is in play and cannot be had, why not" and returns null both for
  *the server never asked for mosh* and for *everything is fine*. The two are then
  indistinguishable, so a server with the toggle off fell through to the bootstrap
  anyway: every plain SSH session paid a multi-second delay, and could be handed a mosh
  session it never asked for.

- **A branch that yields the same value for "no" and for "yes" cannot gate on it.** The
  preference itself has to be a separate, explicit check — `if (!server.useMosh) { ssh;
  return }` — and the availability check is a separate question after it. Splitting the
  two is also what makes the log line truthful: it can say *why* mosh was skipped instead
  of skipping silently.

## A session's transport cannot be changed, only replaced

- **ssh and mosh are separate processes speaking separate protocols**, so "switch this
  session to mosh" cannot mean anything except "open another one and close this one".
  The menu item says **Reconnect** for that reason. Anything less leaves a mosh process
  labelled as ssh, or an ssh session quietly claiming to be mosh.

- **`SessionDescriptor.Ssh.useMosh` is written by `prepareMosh` and nowhere else**, so it
  is the truth about what connected rather than a copy of the preference. That is what
  makes the title safe to derive from: it reports the transport in use, which is the
  interesting case — a server whose card says *mosh* and whose session says *· ssh* has
  told the user exactly the thing they needed to know, with no extra state.

- **The transport suffix is display-only and lives outside `sessionLabel`.** That function
  is the session's identity and feeds the exported transcript's filename; a suffix that
  changed when a session reconnected would give one session two file names. `updateCwdTitle`
  has to use the decorated label too, because it runs on every cwd poll and would
  otherwise drop the suffix the first time the remote changed directory.

- **A silent fallback is indistinguishable from a preference that was ignored**, so the
  reason is both logged and toasted. The session is about to look perfectly normal three
  seconds after the tap, and that instant is the only chance to say why mosh is not in use.

## Copying a field by hand is not persistence

- **The same bug has now happened three times**, each one a field added to
  `SshStore.Server` and forgotten somewhere the server travels:
  1. added to `Entry`/`Server` and the reader but not the writer, so every protected key
     loaded as unprotected and the server had accepted it reported as refused;
  2. `useMosh` on `Server` and on `SessionDescriptor.Ssh`, written and read correctly in
     JSON, and then dropped on the way through the **Intent** that opens a session;
  3. `useMosh` on the Intent, and then dropped anyway by `launchSsh`, which hand-copies
     six fields into extras.

- **None of them has a symptom in one place.** A server whose card says *mosh* opens
  over ssh; the card and the session title are on different screens, so neither looks
  wrong on its own and nothing says to go looking for a mismatch. The only reason this
  was found is that the indicator was added at the same time and made the disagreement
  visible — **a field that is silently lost is indistinguishable from a field that was
  never set**, which is exactly why the transport indicator was worth building even
  though it was not part of the original bug.

- **The fix is not to add the field to one more list.** `launchSsh` carries a
  hand-maintained subset of a nine-field record, and a list of nine is nine chances. The
  saved record is now looked up by id and **wins** over whatever the intent carried; the
  extras are the fallback for a server that is not in the list, which is the only case
  they were ever needed for. `TerminalActivity.resolveSshServer` is the rule and it is a
  pure function with tests.

- **The general form: when one value is copied into another place by hand, prefer
  re-reading the original wherever the receiving end can reach it.** The extras still
  exist and still carry a stale copy, which is fine, because nothing reads it in the
  case that mattered.

## Loopback hides a timer bug that a real network finds in a hundred milliseconds

- **The shim gave up instantly against every real server, and worked perfectly against
  `127.0.0.1`.** The give-up timer was

      const long long silent = last_rx_ms ? now - last_rx_ms : now - start_ms;

  reduced to `now - last_rx_ms` with `last_rx_ms` starting at zero. That looks correct
  and is not: the clock is `CLOCK_MONOTONIC`, so "now" is the milliseconds since boot —
  millions of them — and zero does not mean "now, a moment ago" but "before the machine
  started". The comparison is therefore true on the first iteration and the client exits
  4 in about a millisecond.

- **On loopback the handshake comes back inside that same iteration**, so `last_rx_ms`
  was already set by the time the check ran and the host harness passed every time. A
  real server is a hundred milliseconds and upwards away, so the check ran first. The
  symptom on device was `started a mosh session` immediately followed by
  `exited with status 4`, which reads as the server refusing to speak mosh.

- **A test has to be able to fail for the right reason, and "loopback works" is not a
  test of a timeout.** The case is an endpoint that *accepts but never replies*: a bare
  UDP listener, so there is no ICMP port-unreachable to end it by exception either. With
  that, the old logic exits 4 in under a second and the new one is still waiting when
  killed. Verified both ways by rebuilding the old expression.

- **The first version of that test was wrong in a way worth remembering too.** It fed
  the client `/dev/null` as stdin. The client treats stdin EOF as "the terminal went
  away" and exits 0 immediately — correct behaviour, and a test that passed for an
  entirely unrelated reason. Holding stdin open with `sleep N |` is what makes the
  network the thing under test.

## mosh needs a firewall that ssh alone does not imply

- **A server reachable over TCP 22 is not necessarily reachable over UDP.** The cloud
  instance worked over Tailscale and not over its public address, with the identical
  bootstrap both times: `mosh-server` starts, binds its port and prints `MOSH CONNECT`,
  and then nothing arrives. Tailscale carries the UDP inside its own overlay, so the
  server's own firewall never sees the traffic. That is the whole explanation, and it is
  invisible from inside the app — which is the problem.

- **So a mosh session that produces no output must not be left on screen.** The shim
  exits `4` for "nothing ever came back", opens a dialog naming both causes, opens a
  working ssh session regardless, and offers to either stop asking for mosh for this
  server or just this once. Falling back is not an error path here any more than it is
  at bootstrap time.

- **Both causes have to be named, because fixing the server can be the wrong answer.**
  Measured on a cloud instance over four attempts: the app sent UDP to the right public
  address, the host firewall was opened for `60000:61005` and persisted, and the packet
  counter on that rule stayed at **0** throughout while tcpdump on the interface saw
  nothing and the rest of the INPUT chain counted thousands of packets. So the packets
  never arrived at all — which no server-side configuration can fix, because either the
  cloud's security list is still closed or the phone's own network is refusing to carry
  UDP. TCP 22 works in both cases; only mosh needs the return path, and a client behind
  carrier-grade NAT has no UDP mapping for a flow it did not open. mosh over the same
  server's Tailscale address works throughout, because it tunnels over a connection the
  phone initiated — which is the shape of the difference, and not an accident.

- **`-C` is never passed to a mosh bootstrap**, which is the one deliberate departure
  from the SFTP command line it otherwise mirrors exactly. Compression is negotiated
  over the SSH channel, and this channel carries one connect line and then closes: the
  session proper is UDP with its own authenticated encryption. So it compresses nothing,
  and a server without zlib answers it with `Compression not supported, disabling.` —
  which is **OpenSSH's** message, not mosh's (the string appears nowhere in mosh 1.4.0's
  source), emitted onto the very stream the bootstrap is parsing. mosh's own wrapper
  does not offer compression at all.
- **Mirroring a command line is not mirroring a handshake.** The bootstrap copied
  SFTP's arguments to the letter, and rode along on half of the BatchMode/askpass
  pairing: `BatchMode` was dropped for a held secret, as SFTP drops it, but the
  askpass environment that answers the prompt was never attached — ssh was allowed
  to ask a question with no way to hear the answer, and every passphrase-protected
  key and password server exited 255 into the SSH fallback. `SshAskpass.environment`
  goes with every `SshClient.run` that can omit BatchMode; the SFTP client and
  `runTest` wired it, and the bootstrap now does too.

## "Nothing at all" and "something arrived" are both the wrong signal

- **The shim's "is this session alive" check was `did we render anything`, and the very
  first render always differs** — the remote's terminal size is not the one we were
  seeded with, so mosh emits a diff before a single packet has arrived. Timing the
  give-up on that meant the "waiting for the session" state ended almost immediately, and
  a blocked UDP path was reported as a session that had *stopped*. Symptom on device: an
  empty terminal that stayed empty for 57 seconds and never produced the dialog, while the
  app was confident a session existed.

- **The signal has to be whether the network spoke to us**, which is what
  `mosh_session_is_connected` already answers — the worker sets it only after a
  successful `recv()`. A framebuffer diff is ours; a received packet is the server's.

- **There is now exactly one timer, and it is about one thing.** Waiting 20 s for the
  first byte from the server, then never again. A mosh session is silent most of the
  time somebody is reading a man page, and killing it for that would break the one thing
  mosh exists for. mosh's own client has no such timer either.

- **A blank terminal has to say something — and in this app the place it says so is
  not the client.** The client used to print `mosh: connecting to <ip>:<port>…` to
  stdout and erase it with `\r\033[2K` on the first frame. That line and all of its
  erase bookkeeping have since been removed, on purpose: a status printed into the
  terminal competes with the shell's output for the same screen (one far-end scroll
  strands the rewritten line at the top and the shell prints underneath), and during
  a connect there is no far end to scroll anything — the terminal belongs to the
  client alone. Connecting is the app's business: `TerminalActivity`'s overlay covers
  the attach and ends on the session's first drawn output, so the terminal is blank
  for nobody. The client prints only the failure report (`say` in
  `redterm_mosh_main.cpp`), which is the one text the terminal is the right place for.

## A failure the user never reads is a failure nobody can fix

- **The library prints `[Process completed (code N) - press Enter]`, and we destroy the
  session before anybody can read it.** `handleSessionFinished` removes the session as
  soon as the process exits, so the stock acknowledgement is gone with it — and it
  contains a status number and nothing else, which is not a diagnosis. The mosh client
  therefore prints its own explanation and waits for a keypress before exiting, bounded
  so a session nobody is watching still closes.

- **Pressing Enter *before* the explanation is not an acknowledgement of it.** The client
  is still in its main loop then, and a keypress there is ordinary terminal input queued
  for a session that will never answer — so it is consumed and the failure still waits.
  A test that sends the key "some time during the handshake" therefore passes nothing;
  it has to send it after the message, which is what a user does.

- **The Enter the report promises survives the report's own process, and nobody else
  is left to answer it.** `wait_for_ack` consumed the key that closed the failed
  client, but the text stays on screen and the user keeps pressing — a dead session's
  stdin is a buffer nobody reads, so every further Enter is eaten and the promise
  ("close this session and open one over SSH") goes unkept. The app answers now:
  dismissing the failure dialog *without* opening an ssh session arms the promise,
  and an Enter aimed at that dead session is consumed at the one place the library
  hands out keys before they reach the terminal — `TerminalViewClient.onKeyDown`
  (key events) and `onCodePoint` (an IME's committed Enter, which the library
  normalises to `\r`, code point 13). The answer is guarded: the keypress must be
  aimed at the session the report belongs to, and that session must still be dead,
  so a live session behaves as a terminal and a replaced session inherits nothing.
  A cancel must also mean *stay with the mosh preference* — Enter opens SSH the way
  "this time only" does; "stop trying" is the only path that writes the preference.

- **The progress line the client once printed is gone, and the reasons it was built
  are gone with it.** A blank terminal for twenty seconds is no longer
  indistinguishable from a hung one, because the app's overlay covers that wait and
  ends on the session's first drawn output. What the removal fixed of its own making
  is worth remembering, because each looks like a good idea again: a status
  printed before `TerminalView` had built its emulator went out before there was
  anything to draw to, and a status left unerased ended up as litter under the
  shell's output. None of it is worth resurrecting; the client keeps only `say`,
  the failure report, which is used nowhere else.

- **`exit` at the remote shell was being said goodbye to, and nothing was listening.**
  On a real server typing `exit` left the session stuck at `logout` for ever, and no
  error-based detector could ever fix it, because by the time the shell exits *every*
  packet state is acknowledged — SSP sends nothing more, the network falls silent in
  both directions, an error-based detector never hears an error, and "five consecutive
  receive errors" never happens. mosh has its own protocol shutdown the whole time:
  `mosh-server` sees the pty close, calls `start_shutdown()`, and sends special packets
  with `new_num = uint64_t(-1)` until acked; the client's ack then flips to -1, which is
  what `counterparty_shutdown_ack_sent()` reports. The real mosh client's loop breaks
  clean on exactly that (plus the two shutdown-timer combinations) — owning it meant
  reading mosh's `stmclient.cc`, because nothing in any trace mentions shutdown. The
  client now checks the atomic the worker samples off those calls and exits 0 without
  printing its failure report; the ICMP-port-unreachable detector (five errors in a
  row, then give up) stays as the crash fallback, never the primary signal. Verified
  both directions against a real `mosh-server` on the build host: the pre-fix shim was
  still running at 50 s when it was killed, the post-fix one said a clean goodbye and
  exited 0 in under five seconds.
- **If every packet of both the shutdown and its ack is lost mid-flight, the result is
  two-way silence the protocol cannot tell from a live-but-idle session** — fixing
  that would need a timeout that kills genuinely quiet sessions, the trade the
  idle-timeout removal above already rejected. Deliberately unfixed.

## The overlay ends where the session makes it false

- **A plain ssh connect does its slow work *after* the session process starts.**
  `prepareSsh` picks keys and writes a launcher — milliseconds. Overlaying that meant
  the indicator ended exactly when it stopped being needed, and the seconds of TCP,
  key exchange and authentication ran in full view on a blank terminal. The overlay
  now outlives the attach and ends on the session's **first drawn output**: the
  banner, the prompt, or ssh's own in-terminal passphrase question — which is also
  why it must end on *any* first output, not on "success" only: the prompt is output
  the user needs to read and answer.
- **A mosh session skips the wait no longer.** The Ready path used to hide the
  overlay at attach time because the client was believed to draw its own connecting
  status — which stopped being true the day the progress line was removed, and left
  a blocked-UDP session as twenty seconds of nothing. It now waits the same way a
  plain ssh session does: the first frame, the failure report, the session's death
  or the 30-second cap is what ends it.
- **The library's `onTextChanged` is a faithful "the remote spoke" signal** —
  confirmed from the v0.118.3 bytecode: the *only* callers of
  `TerminalSession.notifyScreenUpdate()` (which is the only caller of
  `client.onTextChanged`) are the reader thread after `TerminalEmulator.append` and
  the session's own `write`/echo path. `TerminalEmulator.resize` does not fire it,
  so a layout-induced resize cannot masquerade as remote output. When an event
  looks ambiguous, read the bytecode before inventing a guard for a race that does
  not exist.
- **Ending on first output still needs a cap.** A TCP connect to a black hole
  produces no output at all, so the 30-second timeout is what stops a silent
  connect, not the output signal. And a session that dies without ever producing a
  line — refused connect arriving as a bare process exit — reports through
  `onSessionFinished`, which also clears the wait; output, death and timeout are
  the three ends, and only "never attached" is not one.
- **Cancel has to mean the same thing at every point of the wait, and attempts
  cannot share one flag.** Cancelling during the *attach-wait* also stops the
  session the overlay sits on (`finishIfRunning`) — recorded in
  `deliberatelyClosed` first, because the kill reports as status -9 and would
  otherwise come back as a failure dialog for the cancel the user just chose.
  Discarding results is by **generation token**, not a boolean: a shared
  `attemptAbandoned` could not tell two attempts apart, so a stalled attempt
  cancelled by the user went on to pass the reset flag a newer attempt began.
  Each `showConnecting` returns a token, Cancel invalidates only the token it
  was shown for, and a worker attaches only when its token is still the newest.
  State that is answered by "the next phase will consume it" is stale the moment
  phases can be missed; state that is answered by "probably the newest attempt"
  is stale the moment two attempts overlap.
- **A fresh attempt also clears the attach-wait.** `showConnecting` calls
  `stopConnectWatch` before showing: an earlier ssh session's first output would
  otherwise end the new attempt's overlay, and cancelling would end a session
  from an attempt the user is not looking at.

## Known follow-ups (deliberately out of scope)

- The bundled ssh binary still carries the build host's sysconfdir compiled in
  (`/tmp/.../ssh-<arch>/etc`), printed by its own `-v` trace — filtered at the test
  card, but a rebuild would remove it at the source. When the rebuild runs, give
  ssh's configure `--prefix=/usr` and `--sysconfdir=/etc/ssh` — the script still
  builds with the work-directory paths that are baked into the shipped binaries.
  It has not been run because `/tmp/redterm-openssh` (sources and the stamped
  OpenSSL trees) was wiped, so it costs a full OpenSSL fetch and four ABI builds
  of hours on one core.
  When it runs, bump `SshClient.CLIENT_VERSION` so devices re-extract. The client
  then begins reading the distro's real `/etc/ssh/ssh_config`, which is why
  `HashKnownHosts=no` is pinned in `SshLaunchOptions.BASE_OPTIONS` — Debian-family
  configs hash new entries, and the known-hosts screen would list them as `|1|…`
  gibberish.

- Split view is still exactly two panes.
- Remote recursive search is one level only: each level is a round trip.
- Downloading a whole remote folder is not offered; the files inside it are.
- **`images/index.json` triggers a full catalogue rebuild.** `select-distributions.sh` decides
  what to build from the changed files, and an index regeneration is not a file it recognises,
  so it falls back to rebuilding all twenty recipes. Every one of them then overwrites a
  published asset that was built and checked on this host, on an x86-64 runner with foreign
  architectures under qemu. Recognise it as a build artifact and skip it, or scope rebuilds
  explicitly — otherwise the next person to regenerate the index triggers it without knowing.
- openSUSE's base is `opensuse/leap:16.0`, a **floating tag**. Two builds of the same commit
  can pull different layers, which is why the two sets of images are not guaranteed identical.
  Arch and ARMtix are pinned (a dated registry tag, a dated release tarball). proot-distro pins
  openSUSE by digest — resolve the manifest, select the per-arch digest, pull `repo@digest` —
  and so should we.