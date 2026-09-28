# Third-party notices

RedTerm bundles and links against third-party software. This file lists the
components, their licenses and their sources. See each license's full text
for the complete terms.

## Bundled native binaries

| Component | Purpose | License | Source |
| --- | --- | --- | --- |
| proot | Userspace root emulation for running distros | GPL-2.0-or-later | https://github.com/proot-me/proot |
| talloc | Memory pool used by proot | LGPL-3.0-or-later | https://github.com/talloc-project/talloc |
| libfakeuid | uid spoofing for proot | GPL-2.0-or-later (derived from proot) | bundled with proot |
| OpenSSH `ssh`, `ssh-keygen` | SSH client used for saved-server connections | BSD-2-Clause | https://github.com/openssh/openssh-portable |
| OpenSSL (statically linked into the above) | Cryptography for the SSH client | Apache-2.0 | https://github.com/openssl/openssl |

proot and its dependencies are cross-compiled for all four ABIs via
`native/build-proot.sh`.

The OpenSSH client is cross-compiled from source for all four ABIs via
`native/build-openssh.sh`, which also applies `native/openssh-fixes.py` for
Android/bionic compatibility. The binaries ship as assets and are extracted to
private storage on first use. `native/ssh_compat.c` supplies an
`explicit_bzero()` replacement, because bionic declares `bzero()` only as a
macro and so OpenSSH's own implementation cannot compile.

The binaries are unpacked into a minimal rootfs under the app's private
storage and executed through proot's `-L` loader, since Android 12+ mounts app
storage `noexec`. Only the client binary is required there, because it is
statically linked. The client is distributed under the terms above; no OpenSSH
source is modified outside the documented Android/bionic fixes in
`native/openssh-fixes.py`.

## Java / Kotlin libraries

| Component | License | Source |
| --- | --- | --- |
| AndroidX Core, Lifecycle, Activity, AppCompat, ConstraintLayout, Preference, ViewPager2 | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| Material Components for Android | Apache-2.0 | https://github.com/material-components/material-components-android |
| termux-app terminal-emulator | Apache-2.0 | https://github.com/termux/termux-app |
| termux-app terminal-view | Apache-2.0 | https://github.com/termux/termux-app |
| commons-compress | Apache-2.0 | https://commons.apache.org/proper/commons-compress/ |
| xz for Java (org.tukaani) | Public domain | https://tukaani.org/xz/java.html |
| anrwatchdog | Apache-2.0 | https://github.com/SalomonBrys/ANR-Watchdog |

## Bundled fonts

| Font | License | Source |
| --- | --- | --- |
| JetBrains Mono | SIL OFL-1.1 | https://github.com/JetBrains/JetBrainsMono |
| Fira Code | SIL OFL-1.1 | https://github.com/tonsky/FiraCode |
| Source Code Pro | SIL OFL-1.1 | https://github.com/adobe-fonts/source-code-pro |
| Ubuntu Mono | Ubuntu Font License-1.0 | https://github.com/canonical/ubuntu-font-family |
| Droid Sans Mono | Apache-2.0 | https://android.googlesource.com/platform/frameworks/base |
| Noto Sans Mono | SIL OFL-1.1 | https://github.com/notofonts/noto-fonts |
| Cascadia Code | SIL OFL-1.1 | https://github.com/microsoft/cascadia-code |

## Note on the GPL

The proot binary is licensed under the GPL. RedTerm ships proot as a
separate binary executed by the app, and the source of the exact binaries
used is available in this repository (`native/`) and upstream at the
sources above. If you believe any component above is listed incorrectly,
please open an issue.
