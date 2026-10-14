# proot-distro reference findings

Everything here was derived from `termux/proot-distro` and verified on this machine
against real images. Nothing is inferred from reading alone.

The reference source used is a plain directory copy at
`/home/ubuntu/proot-distro-ref/src/`. It reports `PROGRAM_VERSION="4.34.1"`, and
`bootstrap-rootfs.sh`, `distro-build/archlinux.sh` and `distro-build/opensuse.sh`
are **byte-identical to tag v4.34.2**; `proot-distro.sh` differs only in the version
string. So the local copy is a sound citation for the v4.34.2 release.

Verify with:

    diff -u /home/ubuntu/proot-distro-ref/src/bootstrap-rootfs.sh \
            <(curl -sfL https://raw.githubusercontent.com/termux/proot-distro/v4.34.2/bootstrap-rootfs.sh)

## 1. The install extractor was destroying every mode

This is the root cause of the pacman warnings seen on device:

    warning: directory permissions differ on /tmp/
    filesystem: 755  package: 1777
    warning: directory permissions differ on /root/
    filesystem: 755  package: 750

RedTerm has **two** extractors and only one of them was correct.

| path | extractor | directory mode |
|---|---|---|
| restore | `TarExtractor.kt` | `mode and 0xFFF`, **deferred deepest-first** |
| install | `DistroInstaller.extractTarEntries` | **`chmod(target, DIR_MODE)` — always 0755** |

`DistroInstaller` read `entry.mode` for files, reduced it to a boolean `isExec`, and
then threw it away in favour of 0755/0644. Directories never had their mode read at
all. The archive's permissions were replaced with one guess.

proot-distro's install is the reference for doing this correctly
(`proot-distro.sh:498,530,535`):

    --delay-directory-restore --preserve-permissions

`--preserve-permissions` restores the archived mode; `--delay-directory-restore`
applies directory modes **after their contents**, which is required — a directory
recorded as `0555` cannot have children created inside it if its mode is applied first.

Nothing in proot-distro's install chmods anything. There is no sweep.

### The published images were always correct

Read directly out of the shipped tarballs. From `armtix-aarch64-rt-v1.0.0.tar.xz`:

    1582 drwxr-xr-x     9 drwx------     5 drwxr-x---     5 dr-xr-xr-x
       3 drwxrwxrwt  <- sticky (1777)      1 drwxrwsr-x  <- setgid (2755)

`/root` is `750`, `/etc` is `755`, and sticky and setgid survive. The build was never
the problem, which is why the image-side changes could not fix it.

## 2. `-perm -400` is owner **execute**, not setuid

`bootstrap-rootfs.sh` in proot-distro contains:

    find "${2}" -type d -perm -400 -print0 | xargs -0 sudo chmod 755

`find` parses a `-perm` mode as **octal**. `0400` is bit 8, which is the owner's
execute bit. It is not setuid; setuid is `04000`.

Measured on this machine:

    find /usr -type d -perm -400    ->  22197 matches
    find /usr -type d -perm /0100   ->  22197 matches
    find /usr -type d -perm /4000   ->       0 matches
    total dirs under /usr           ->  22197

So it matches every traversable directory. Our own
`bootstrap-rootfs.sh` comment claimed it means "the owner CAN read", which is also
wrong; it is the execute bit. The replacement (`chmod u+rx` on directories missing
either bit) is still correct and still better, but the comment justifying it was not
true.

## 3. openSUSE: every fix so far was built on a cause that does not exist

Tested natively (the host is aarch64, the images are aarch64) in a `chroot`.

Our published `opensuse-aarch64-rt-v1.0.0.tar.xz` and proot-distro's
`opensuse-aarch64-pd-v4.34.2.tar.xz` have **byte-identical** `repos.d` files and the
same `openSUSE.service`. The only difference is that ours ships
`/var/cache/zypp` empty, where theirs has `packages/ raw/ solv/`.

`zypper --non-interactive refresh` results:

| configuration | result |
|---|---|
| our image, as published (colons, service, empty cache) | **succeeds** |
| proot-distro's image, unmodified | **succeeds** |
| our image + RedTerm's `opensuseClean` applied | **succeeds** |
| second refresh (idempotency) | **succeeds**, "up to date" |

With the stock configuration it creates exactly the path the error claimed was
impossible:

    /var/cache/zypp/solv/openSUSE:repo-oss/solv
    /var/cache/zypp/solv/openSUSE:repo-oss/solv.idx
    /var/cache/zypp/solv/openSUSE:repo-oss/cookie

and `zypper search --match-exact curl` then answers from that cache.

So each stated cause is disproven:

- **the colon in the alias** — not a cause; libzypp creates colon-named cache
  directories without difficulty
- **`openSUSE.service`** — not a cause; it works with and without it
- **an absent `/var/cache/zypp` tree** — not a cause; libzypp creates it
- **`RepoManager.db`** — irrelevant; zypper rebuilds it

The reported `Curl error (28)` is a network timeout, not a configuration fault. Both
the HTTP and HTTPS baseurls for Leap 16.0 aarch64 answered in under a second from here.

The one mechanism that remains plausible and is **not** disproven is writability: a
session runs as the app's uid, while the image builds these directories as root. That
is a real reason to keep making the cache writable, and it cannot be tested here —
`zypper` refuses to run without uid 0, and the app depends on proot's `--root-id` to
fake it. Everything else in `opensuseClean` is speculation about aliases and services
that demonstrably work.

### One real bug found there

`opensuseClean` runs:

    rm -f /etc/zypp/services.d/openSUSE.service /usr/share/zypp/local/service/openSUSE

`/usr/share/zypp/local/service/openSUSE` is a **directory**, so `rm -f` fails and
leaves it in place while the script reports nothing useful:

    rm: cannot remove '/usr/share/zypp/local/service/openSUSE': Is a directory

## 5. Defects found in our own recipes while checking main

Checked `GlobalTechInfo/rootfs-images` `main` at `8cf336a` (PRs #22 and #23 merged
2026-10-13). The published images are dated **2026-10-09**, so every image predates
every merge and a rebuild is required for any of it to reach a device.

### ARMtix inserts `DisableSandbox` twice — PR #24

`distro-build/armtix.sh` applied the `DisableSandbox` / `DownloadUser = root` pair
**twice**: once above the `XferCommand` block, and again near the end after the
sync-database removal. The earlier copy ran

```bash
sudo sed -i '/^\[options\]/a DisableSandbox' .../etc/pacman.conf
```

with no matching delete. `sed`'s `a` appends unconditionally, so the built image
carries the directive twice and pacman reports the duplicate on every command —
the same noise `DistroSetup` version-guards against on Manjaro. Arch's recipe
applies the pair once and is unaffected.

Fixed by keeping only the later, better-explained copy, in delete-then-insert form
so the step is idempotent.

### openSUSE's rewrites removed — PR #25

`distro-build/opensuse.sh` de-coloned every alias, renamed the `.repo` files,
stripped `service=`, deleted `openSUSE.service` and `RepoManager.db`, then deleted
and recreated `/var/cache/zypp`. Section 3 shows all of that addresses causes that
do not exist. Removed; only https and a writable cache survive.

The cache permission move is deliberate and follows the principle the ARMtix
recipe already states: an image that is broken until a particular build of the app
repairs it is not a working image.

### Arch's `i686` drop is already reflected

`distro-build/archlinux.sh` now builds `aarch64 arm x86_64` and warns that
`DistroRegistry` must be narrowed to match. It already is:

```kotlin
// DistroRegistry.kt, the "arch" entry
prootArchs = listOf("aarch64", "arm", "x86_64"),
```

Nothing to change there.

### Why the hooks still need a rebuild, not an app-side fix

The published ARMtix image ships 26 hooks under `/usr/share/libalpm/hooks`,
including `90-mkinitcpio-install.hook` — the source of the mkinitcpio warnings.
`NoExtract` in `pacman.conf` cannot help at runtime, because those files are
**already in the image**: `NoExtract` only stops pacman writing a path again, it
does not remove one that is there. Deleting them from the app was already tried
and withdrawn — it produced `could not get file information` and came back on
every upgrade. The recipe's `HookDir = /etc/pacman.d/hooks` plus build-time
`NoExtract` is the right shape; it simply has not been built yet.

## 6. proot-distro bakes configuration; RedTerm re-derives it per login

Arch's first login is the entire runtime setup
(`distro-build/archlinux.sh:104`): a PAM `readenv=1` line and `locale-gen`.
openSUSE's is one command (`distro-build/opensuse.sh`): `zypper al filesystem`.

`XferCommand` and `DownloadUser` appear **zero times** in `proot-distro.sh` and all 17
recipes. Arch sets exactly one pacman directive, `DisableSandbox`, at build time
(`distro-build/archlinux.sh:33,80`). RedTerm instead maintains a version-guarded
`XferCommand` at every session start, carrying four documented bugs in its history.

openSUSE is pinned by **digest**, not tag (`distro-build/opensuse.sh:7,16`) — resolve
the multi-arch manifest, select the per-arch digest, `docker pull repo@digest`. That is
stronger than a sha256 list checked against a fixed release URL.

## Reproducing

Both images and the extracted trees live in `/tmp/opencode/ab/`. That is tmpfs and
will not survive a reboot.

    pd-opensuse.tar.xz   proot-distro opensuse-aarch64-pd-v4.34.2   (43,983,352 B)
    pd-arch.tar.xz       proot-distro archlinux-aarch64-pd-v4.34.2  (163,892,644 B)
    pd-os/  our-os/  our-os-test/     extracted trees
    inchroot.sh          sudo mount --bind /proc,/sys,/dev then chroot
    bin/opensuseClean.sh the DistroSetup.opensuseClean script, reconstructed

Note that a plain `chroot` fails in this environment with no capabilities; every chroot
and mount has to go through `sudo`. `/tmp` is a 2.9G tmpfs, so keep only one or two
trees at a time.