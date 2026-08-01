# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

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
