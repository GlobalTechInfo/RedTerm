# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [v2.0.0]

### Added

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
- **Distro lineup**: Kali and Artix removed; openSUSE added.
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

- **Crash above ~2 GB distros**: four full `rootfs` directory walks (distro-size display on the terminal, main and settings screens, plus the widget) ran on the main thread during layout, which ANR'd on large trees. Size is now cached and scanned in the background, and rootfs repair runs off the UI thread.
- **Foreground-service notification never appeared**: the permission dialog is asynchronous, so the `POST_NOTIFICATIONS` check always failed on first open and the service was never started. See "Changed" for the new flow.
- **ESC and TAB extra keys**: both bypassed the terminal view and wrote to `sessions[currentIndex]`, which could be null or stale. They now go through the focused `TerminalView` (the same path as the working arrow keys), honour the CTRL/ALT toggles, and fall back to a direct code-point write. The extra-key touch handler calls `performClick()` exactly once on release, fixing the earlier double-fire that broke CTRL, ALT and `&&`.
- **Clipboard paste hangs in TUIs**: paste wrote raw text on the main thread; `ByteQueue.write()` blocks once its 4096-byte buffer fills, and without bracketed-paste markers interactive programs (opencode and similar) reprocessed the content line by line. Pastes now use `TerminalEmulator.paste()` on a background executor, so `ESC [ 200 ~ … ESC [ 201 ~` wrapping is applied when the program enables mode 2004.
- **Theme toggle from the terminal**: selecting a theme broadcast a change that called `recreate()`, tearing down the live proot session mid-interaction and intermittently crashing. It now recolours in place, and the menu's "Red Terminal" entry no longer applies the AMOLED theme.
- **Arch package installs failing with `GLIBC_2.43 not found`**: caused by a partial upgrade; first-time setup now performs a full system upgrade.
- **openSUSE `Can't open solv-file`**: the x86-only `repo-openh264` repository was skipped and left a corrupt cache; it is now disabled and the cache is rebuilt.
- **Distro install cancel and resume**: cancelling now deletes the partial rootfs and partial download, while a network or other failure keeps the partial download so Retry resumes; previously every failure discarded the whole download and a cancelled extraction could leave a half-extracted rootfs.
- **Lint: HardcodedText (73)**: all hardcoded strings in XML layouts extracted to string resources.
- **Lint: SetTextI18n (54)**: all programmatic `setText()` calls use `getString()`/`getQuantityString()` with format strings.
- **Lint: RtlSymmetry (2)**: symmetric horizontal padding added in settings layout.
- **Lint: PrivateResource**: the `copy` string renamed to `action_copy` to avoid the clash with `androidx.preference`.
- **Lint: PluralsCandidate**: byte-size strings reworded to avoid quantity-dependent formats; `match_count_for_query` converted to `<plurals>`.
- **Lint: TypographyDashes (2)**: en dashes (`–`) used instead of hyphens (`-`) in PIN strings.
- **Lint: ButtonStyle (3)**: welcome screen buttons changed to Material Button for proper button appearance.
- **Lint: ClickableViewAccessibility (2)**: `v.performClick()` called from the `ACTION_UP` handler and the event consumed so the action fires exactly once.
- **Lint: Overdraw (8)**: redundant root `android:background` removed and each theme now paints its own `android:windowBackground`.
- **Lint: TooManyViews**: `activity_settings.xml` split into included section layouts.
- **Lint: DataExtractionRules**: added a legacy `android:fullBackupContent` resource for API 24–30.
- **Lint: SetWorldReadable**: file modes applied with `Os.chmod` instead of `File.setReadable`.
- **Lint: UnusedResources / AndroidGradlePluginVersion / GradleDependency**: unused strings removed, Gradle and `core-ktx` updated.
- **Lint: UseCompatTextViewDrawableXml (2)**: `android:drawableEnd`/`android:drawableTint` replaced with `app:drawableEndCompat`/`app:drawableTint`.
- **Lint: UseKtx (all)**: `prefs.edit {}` KTX, `.toUri()`, `.isVisible`, and `apply` blocks cleaned up across all files.
- **Lint: SwitchCompat (all)**: `SwitchCompat` used consistently in XML layouts and Kotlin code.
- **Lint: ScrollViewSize**: terminal layout uses `match_parent` height correctly.
- **Lint: SmallSp**: terminal text sizes use `sp` units properly.
- **Lint: ObsoleteSdkInt**: removed unnecessary `Build.VERSION.SDK_INT >= N` guard (minSdk is 24).
- **Lint: WakelockTimeout**: wake lock now uses `acquire(long)` with explicit timeout.
- **Lint: DefaultUncaughtExceptionDelegation**: crash handler properly delegates to the default handler.
- **Lint: PrivateApi (DnsHelper)**: removed reflection-based private API access.
- **Lint: SdCardPath**: replaced hardcoded `/sdcard` with `Environment.getExternalStorageDirectory()`.
- **Lint: RedundantNamespace**: removed redundant `tools` namespace in `ic_back_chip.xml`.
- **Lint: MonochromeLauncherIcon**: added monochrome layer in launcher icon adaptive foreground.
- **Unused resources deleted**: `rounded_bg.xml`, `spinner_bg.xml`, `spinner_dropdown_item.xml` removed.

### Removed

- **Self-built rootfs pipeline**: the `rootfs/` directory (builder scripts and `build.sh`), the `build-rootfs` GitHub Actions workflow, and all references to it are gone — including from earlier commits, which were rewritten with `git filter-repo` to purge the `rootfs/` path.
- **Photo/video permissions**: `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO` and `READ_MEDIA_AUDIO` removed; a terminal has no need for photo-library access and all-files access already covers `/sdcard`.
- **`extractNativeLibs`**: removed `android:extractNativeLibs="true"` from manifest (not needed with current NDK).
- **`requestRawExternalStorageAccess`**: removed from manifest (deprecated, not needed).

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
