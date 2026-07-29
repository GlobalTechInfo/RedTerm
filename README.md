# RedTerm

A terminal emulator for Android that runs Linux distributions (Alpine, Debian, Ubuntu, Arch, Fedora) via **proot** — no root required.

Built from the [ReTerminal](https://github.com/rustDeku/ReTerminal) proot source with the `POKEDATA_WORKAROUND` for ARM64, `SECCOMP_FILTER`, and `HAVE_PROCESS_VM` support.

## Features

- Multiple Linux distros installable from the app
- Proot-based execution — no root required, no system modification
- Full terminal with extra keys row
- Multi-session support with drawer switcher
- Three color themes (Catppuccin Dark, Green Terminal, Light)
- Foreground service with notification controls
- Font size adjustment
- Haptic feedback on key press
- **Auto-init**: first-time distro setup installs packages (nano, curl, wget, git, openssl, bash) and writes a full `.bashrc` with aliases, colored prompt, and completion

## Screenshots

| Home | Installed | Terminal |
|:----:|:---------:|:--------:|
| ![Home](screenshots/home.jpg) | ![Installed](screenshots/installed.jpg) | ![Terminal](screenshots/terminal.jpg) |

## Building

```bash
# Set ANDROID_HOME to your SDK location
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
```

The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

### Building proot from source

Proot is cross-compiled for Android using the NDK. See `native/build-proot.sh` for the build script. It:

1. Builds a static talloc library (from Samba)
2. Patches `loader/loader-fix.h` for PATH_MAX on newer NDKs
3. Compiles proot with `TALLOC`, `SECCOMP_FILTER`, `POKEDATA_WORKAROUND`, and `PROCESS_VM` support
4. Strips and installs to `app/src/main/jniLibs/<abi>/`

Pre-built binaries for `arm64-v8a` and `armeabi-v7a` are included in the repo.

## Distro support

| Distro | Status | Package manager | Init |
|--------|--------|-----------------|------|
| Alpine | Working | apk | `apk add nano curl wget git sudo openssl bash` |
| Debian | Working | apt | `apt-get install nano curl wget git sudo openssl bash` |
| Ubuntu | Working | apt | Same as Debian |
| Fedora | Working | dnf | `dnf install nano curl wget git sudo openssl bash` |
| Arch   | Untested | pacman | `pacman -S nano curl wget git sudo openssl bash` |

## How it works

1. The app extracts a rootfs tarball to its private data directory
2. `launch.sh` sets up environment variables (`PROOT_LOADER`, `PROOT_TMP_DIR`, `ENV`, `PATH`)
3. proot starts with Android's `/system/bin/sh` in the chroot
4. The Android shell sources `/root/.startup` (via `ENV`) — runs first-time setup if needed
5. The user can type `bash` to switch to Alpine's bash with full `.bashrc`

## Known limitations

- The app's data directory is typically mounted `noexec` on Android 12+
- proot's `-L` (kompat) flag handles noexec by loading binaries through `libproot-loader.so`
- Linker warnings about `/linkerconfig/ld.config.txt` are cosmetic and suppressed by bind-mounting the file

## License

GPL-3.0
