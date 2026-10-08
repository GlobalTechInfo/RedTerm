# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
