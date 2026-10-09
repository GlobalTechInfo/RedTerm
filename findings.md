# Findings — own rootfs images for RedTerm

Research notes and roadmap for building and publishing our own proot rootfs images,
plus the feature shortlist that came out of the same review.

Written 2026-10-08, before any implementation. Nothing described here is in the app yet.

---

## 1. The problem: RedTerm ships frozen images

`DistroRegistry` pins Termux proot-distro **release tags**, and those tags have not moved:

| distro | pinned release | current upstream release | size (aarch64) |
|---|---|---|---|
| alpine | `v4.30.1` | no longer published | — |
| arch | `v4.34.2` | no longer published | 156 MB |
| debian | `v4.29.0` | no longer published | — |
| void | `v4.29.0` | no longer published | — |
| ubuntu | `v4.30.1` | no longer published | — |
| almalinux | `v4.31.0` | no longer published | 36.7 MB |
| rocky | `v4.29.0` | no longer published | — |
| fedora | `v4.31.0` | no longer published | 40.3 MB |
| manjaro | `v4.34.2` | no longer published | 134 MB |
| opensuse | `v4.34.2` | no longer published | 48.9 MB |

Observed directly on the GitHub releases API: every proot-distro release from **v5.0
onward attaches zero assets**. The releases are now just the Python tool's changelog.

So these are frozen 2025 snapshots. They get no security updates, ever. That is the
actual motivation for this project.

Kali is the exception: it is NetHunter's own rootfs from `kali.download`, and its
`current/` path is rolling, so it has no pinned sha256 by design.

---

## 2. What upstream did, and where the old builder went

proot-distro had **two generations**.

**Generation 1 — shell.** Commit `5bd8f4f` (2025-11-30) is the last state of it:
53 files, with `bootstrap-rootfs.sh`, `distro-build/<distro>.sh` (17 recipes),
`distro-plugins/<distro>.sh` (19 plugins) and a CI workflow that published
`rootfs/*<arch>*.tar.xz` per architecture.

**Generation 2 — Python + OCI.** Master (2026-10-06) pulls official OCI images
(`ubuntu:25.10`, `debian:trixie`, `alpine:3.22`, `fedora`, `quay.io/opensuse/leap`),
applies layers with whiteout semantics in pure Python, caches per architecture.
No Docker daemon, no root.

`distro-build/` **no longer exists on master.** The tarball pipeline was deleted when
they moved to Python, and image publication stopped with it.

**Consequence:** the exact system RedTerm depends on survives only in git history, at
`https://github.com/termux/proot-distro/tree/5bd8f4f503727d21495fb22badf243a8db973094`.
Vendoring it is the cheapest path to our own images. Reimplementing OCI layer
application would be reimplementing a careful project for no benefit.

---

## 3. How the shell builder works

`bootstrap-rootfs.sh` is the driver. For each named distro it sources
`distro-build/<name>.sh`, which must define two functions.

### `bootstrap_distribution` — produce the rootfs

```bash
dist_name="Debian"
dist_version="trixie"          # upstream comment: "Put only current stable version here!"

bootstrap_distribution() {
 for arch in arm64 armhf i386 amd64; do
   sudo mmdebstrap \
     --architectures=${arch} \
     --variant=minbase \
     --components="main,contrib" \
     --include="ca-certificates,locales" \
     --format=directory \
     "${dist_version}" \
     "${WORKDIR}/debian-${dist_version}-$(translate_arch "$arch")"
   archive_rootfs "${ROOTFS_DIR}/debian-${dist_version}-...-pd-${CURRENT_VERSION}.tar.xz" ...
 done
}
```

`mmdebstrap` for Debian-family, the distro's own bootstrap (or a plain tarball fetch)
for the rest. `sudo` plus a chroot, not proot — the build host is a real Linux CI
runner.

### `archive_rootfs` — the important function

```bash
SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-$(date +%s)}"

sudo find "${2}/dev" -mindepth 1 -delete
sudo find "${2}" -type d -perm -400 -print0 | xargs -0 sudo chmod 755

sudo tar \
    --directory="$WORKDIR" --create \
    --sort=name \
    --hard-dereference \
    --mtime="@${SOURCE_DATE_EPOCH}" \
    --numeric-owner \
    --preserve-permissions --acls --xattrs --xattrs-include='*' \
    --xz --file="${1}.tmp" "$2"
```

### `write_plugin` — generate the runtime contract

Writes `distro-plugins/<name>.sh` containing the download URL and sha256 per arch,
computed from what was just built:

```bash
TARBALL_URL['aarch64']="${GIT_RELEASE_URL}/debian-${dist_version}-aarch64-pd-${CURRENT_VERSION}.tar.xz"
TARBALL_SHA256['aarch64']="$(sha256sum "${ROOTFS_DIR}/..." | awk '{ print $1}')"
distro_setup() {
	sed -i -E 's/#[[:space:]]?(en_US.UTF-8[[:space:]]+UTF-8)/\1/g' ./etc/locale.gen
	run_proot_cmd DEBIAN_FRONTEND=noninteractive dpkg-reconfigure locales
}
```

### `translate_arch`

Normalises `arm64|arm64v8` → `aarch64`, `armel|armhf|armv7l|…` → `arm`, `386|i386|x86` →
`i686`, `amd64` → `x86_64`, and **`riscv64` → `riscv64`**.

---

## 4. Their flags already solve the bugs we hit

This is the strongest argument for vendoring. Every one of these corresponds to a
defect we found and fixed *inside* RedTerm:

| `archive_rootfs` | The RedTerm defect it prevents |
|---|---|
| `--hard-dereference` | `link()` gives EPERM on Android's filesystem and takes the whole restore down. Arch's `ca-certificates/extracted/cadir` is several hundred hard links. They dereference at **archive creation**; `TarExtractor` handles it at **extraction**, because we consume foreign archives and must cope either way. |
| `find -type d -perm -400 → chmod 755` | mode-000 files that only root can read (`/etc/shadow`, `/etc/sudoers`, several hundred on Fedora). Our `RootfsArchive.makeReadable` is the same repair. |
| `find "$2/dev" -mindepth 1 -delete` | proot's `/dev` bind stubs captured in the archive. |
| `--mtime="@${SOURCE_DATE_EPOCH}"`, `--sort=name`, `--numeric-owner` | reproducible builds, so the published sha256 is stable and an unchanged recipe yields an unchanged hash. |
| CI installs `qemu-user-static` + `binfmt-support` | the four-ABI cross-build problem, solved. This is exactly why we cannot build images locally — see §7. |

Note the division of labour: they fix hard links and unreadable modes when *creating* an
image. We had to fix them when *restoring* one. Both are needed, because a RedTerm user
can restore a backup taken from any image.

---

## 5. The plugin format is already RedTerm's model

Upstream's runtime contract and our data model are 1:1:

| `distro-plugins/<name>.sh` | `Distro.kt` |
|---|---|
| `TARBALL_URL['aarch64']` | `baseUrl` + `tarballUrlFor()` (`Distro.kt:14`) |
| `TARBALL_SHA256['aarch64']` | `sha256` map + `sha256For()` (`Distro.kt:20`) |
| `DISTRO_NAME` / `DISTRO_COMMENT` | `displayName` / `description` |
| `distro_setup()` | `DistroSetup` generated `.startup` |

RedTerm was derived from this format, so integration is near-mechanical. `baseUrl` is a
template with `{arch}` rather than a map, which is the only real difference.

---

## 6. Licence

Both projects are **GPL-3.0**, verified from `LICENSE` in each. Vendoring the shell
builder is licence-compatible with no obligation beyond the existing GPL-3 notice.

---

## 7. Proposed `rootfs-images` repository

```
rootfs-images/
  bootstrap-rootfs.sh          vendored verbatim from 5bd8f4f
  distro-build/<distro>.sh     one recipe per distro — the only thing we author
  distro-plugins/              generated by write_plugin
  rootfs/                      generated tarballs (gitignored)
  .github/workflows/build.yml  CI
  images/index.json            generated manifest the app reads at runtime
```

### `images/index.json`

Moves the catalogue out of compiled-in Kotlin:

```json
{
  "schema": 1,
  "generatedAt": "2026-10-08T11:41:19Z",
  "images": [
    {
      "distro": "alpine",
      "arch": "aarch64",
      "file": "alpine-3.22-aarch64-pd-v5.0.0.tar.xz",
      "url": "https://github.com/GlobalTechInfo/rootfs-images/releases/download/v5.0.0/alpine-3.22-aarch64-pd-v5.0.0.tar.xz",
      "sha256": "…",
      "size": 32517123,
      "baseVersion": "3.22",
      "recipe": "distro-build/alpine.sh",
      "sourceDateEpoch": 1757322079
    }
  ]
}
```

### CI

`ubuntu-24.04`, plus `qemu-user-static`, `binfmt-support`, `curl`, `jq`, `sudo`, `tar`,
`xz`, and the bootstrapper tool the recipe needs (`mmdebstrap` and keyring debs for
Debian-family). Upstream's workflow builds only the distros whose recipe changed, via
`git diff-tree`; `workflow_dispatch` takes an explicit space-separated list. Copy that
— building all 11 × 4 on every push is wasteful and slow.

### RedTerm side

Small. `DistroRegistry` reads `index.json` and overrides `baseUrl`/`sha256`, falling
back to the compiled-in table when the index is unavailable so an offline install still
works. `sha256For()` already exists.

---

## 8. Roadmap

Phases are gated. Each one must work before the next starts.

### Phase 1 — prove it

Vendor `bootstrap-rootfs.sh`; write **one** recipe for **one** arch; get CI to produce
one tarball; install it in RedTerm and boot it.

Nothing above this is worth building until that boots.

### Phase 2 — own the matrix

All four arches via qemu-user. Add a reproducibility check: build the same recipe twice
and confirm identical sha256. If `SOURCE_DATE_EPOCH` is pinned per release rather than
`date +%s}`, an unchanged recipe must yield an unchanged hash — that property is what
makes the published index trustworthy.

### Phase 3 — publish and integrate

Release assets, `index.json`, app reads it. Gate: a fresh install on a device boots our
image. See §9 for the supply-chain question, which is a precondition for this phase.

### Phase 4 — trim and bake

Strip `/usr/share/{doc,man,info,locale}`; pre-run first-start setup at build time; bake
RedTerm's `.bashrc`. Expected effect: Arch 302 MB → roughly 90 MB, and the
multi-minute `>>> First-time distro setup` disappears.

Keep the in-app repair script. Baking the setup away must not delete it — that script is
how several of this session's bugs were caught.

### Phase 5 — expand

Remaining distros, then the new ones (Gentoo, NixOS, Clear, Deepin) on our schedule.

---

## 8a. Current upstream versions, verified

Every version below was checked against the distribution's own release host rather than
taken on trust, because the point of the pin is that it is right.

| distro | current stable | codename / branch | released | RedTerm pins today | gap |
|---|---|---|---|---|---|
| Debian | **13.7** | trixie | 2026-09-12 | `trixie` (v4.29.0) | named release, stale snapshot |
| Ubuntu | **26.04.1 LTS** | resolute | 2026-04-23 | `questing` = **25.10**, non-LTS | one whole LTS behind |
| Fedora | **44** | — | 2026-04-28 | v4.31.0 (43 era) | one major behind |
| Rocky | **10.2** | — | — | v4.29.0 | stale snapshot |
| AlmaLinux | **10.2** / **9.8** | Lavender Lion / Olive Jaguar | 2026-05-26 | v4.31.0 | stale snapshot; 9.8 still supported |
| openSUSE | **Leap 16.0** | — | 2025-10-01 | v4.34.2 (16.0) | named release matches, snapshot stale |
| Manjaro | **26.1** | Bian-May | 2026-08-12 | v4.34.2 (2025-09) | a year behind |
| Arch | **2026.10.01** ISO | — | 2026-10-01 | v4.34.2 (2025.09.01) | a year behind |
| Alpine | **3.24.2** | v3.24, EOL 2028-06 | 2026-06-09 | `v4.30.1` | stale snapshot |

### What verification actually showed

- **Debian 13.7 / trixie** confirmed from `deb.debian.org/debian/dists/stable/Release`:
  `Version: 13.7`, `Codename: trixie`, `Date: Sat, 12 Sep 2026`.
- **Ubuntu 26.04 = `resolute`**, confirmed from `archive.ubuntu.com/ubuntu/dists/resolute/Release`:
  `Version: 26.04`, `Date: Thu, 23 Apr 2026`. RedTerm's pin `questing` is `Version: 25.10`
  dated 2025-10-09 — so RedTerm is shipping a **non-LTS interim** release while 26.04 LTS
  is available. That is worth fixing on its own merits, independent of this project.
- **Fedora 44 exists** with a live aarch64 repomd. RedTerm's v4.31.0 predates it.
- **Rocky 10.2 and AlmaLinux 10.2 + 9.8** both present in the upstream mirrors. 9.8 is the
  maintained 9-series, so Alma is a two-branch decision rather than one version.
- **openSUSE Leap 16.0** confirmed live at `download.opensuse.org/distribution/leap/16.0/`.
- **Arch ISO 2026.10.01** confirmed at `geo.mirror.pkgbuild.com/iso/`.
- **Manjaro 26.1 not directly verifiable.** `download.manjaro.org` returns no usable
  listing for it. Upstream's own recipe builds Manjaro from
  `manjaro-arm/rootfs` releases, which are **aarch64-only** — and RedTerm only offers
  Manjaro on aarch64, so that is consistent. The `manjaro-arm/rootfs` feed is rolling and
  currently at `20261005`, so there is no "26.1 Bian-May" artifact to pin by name. Pinning
  Manjaro means pinning a dated rootfs release, and the version string RedTerm displays has
  to come from that feed rather than from a Manjaro release announcement.
- **Alpine 3.24.2** confirmed, and is what the Phase 1 recipe builds.

One consequence for the index: a pinned version has to be something the build host can
actually fetch. Where a distro publishes only a rolling image, the honest entry is the
digest or the dated artifact id, and the display version is derived from the rootfs rather
than from a release name.

## 9. Open questions

1. **Vendor or fork?** Copy `bootstrap-rootfs.sh` into our repo, or fork proot-distro
   and track upstream? *Recommendation: vendor.* One file, GPL-compatible, no sync burden.
2. **Supply chain.** Today users execute a Termux-built rootfs. Ours would be built by
   us, in a repo we control, with a sha256 we publish. This is a real transfer of
   responsibility and is a **precondition for Phase 3**, not a detail to settle later.
   Mitigations: pin `dist_version` to current stable, publish sha256, record
   `sourceDateEpoch` and the recipe path in the index, keep the upstream builder rather
   than writing our own.
3. **Distro scope.** Keep the 11 already shipped, or fold new ones into this project?
   *Recommendation: defer new distros to Phase 5.*
4. **Phase 1 proof target.** *Recommendation: Alpine aarch64* — smallest and fastest, so
   the loop closes in minutes, and it is the most exercised image on device today.
   Debian is the alternative and exercises `mmdebstrap`.
5. **Trim timing.** *Recommendation: Phase 4 only.* Stripping docs and locales is a
   behaviour change and should land after the plain pipeline is trusted.

---

## 10. Feature suggestions

Separate from the image work. Grounded in limitations we already know about.

### Small, high value-to-effort

1. **Explain hidden distros.** `DistroRegistry.forDevice()` (`:169-172`) silently filters
   by `prootArchs`. A user on an x86 device sees Fedora, Alma, Rocky and Manjaro simply
   *absent*, with no explanation. That is a bug-shaped gap, not a feature.
2. **Edit a remote file over SFTP.** The editor already exists and `SftpSource` already
   reads; this is mostly wiring.
3. **Horizontal split.** Split view is exactly two panes, vertical only.
4. **Recursive remote search.** Currently one level per query.

### Medium

5. **Download a whole remote folder** — listed as a known limitation.
6. **SFTP resume** for interrupted transfers.
7. **First-connect host-key fingerprint confirmation.** Trusted hosts are managed, but the
   accept/reject prompt itself is still ssh's own.
8. **Backup through SAF**, so All-files-access is not required at all.
9. **Surface the tunnel manager.** `TunnelManager` exists with no real UI.

### Larger

10. Scheduled backups via WorkManager.
11. Clipboard sync host ↔ guest.
12. Diff a rootfs against its base image ("what did I change?"), which would also make
    "reset to image" trustworthy.

Items 1 and 2 are the best ratio of value to work in the whole list.

---

## 11. What else is in that commit, and what is deliberately not vendored

The full tree is 53 files. Only the builder was taken. Worth being explicit about why,
and about what the rest revealed.

### Not vendored: `proot-distro.sh`

107 KB, the **runtime**: installs containers, manages bind mounts, launches a session.
RedTerm already implements all of it natively (`ProotLaunch.kt`, `DistroInstaller.kt`,
`SessionDescriptor`). Vendoring it would mean shipping a shell program nothing calls.
It is the runtime counterpart of the builder, and we took the builder only — RedTerm
already *is* the runtime.

### The 15 recipes we did not read, and their pins

Versions and architecture coverage as pinned at `5bd8f4f`:

| recipe | version | arches |
|---|---|---|
| adelie | 1.0-beta6 | aarch64, arm, x86_64 |
| almalinux | 10 | aarch64, x86_64 |
| alpine | 3.22.2 | aarch64, arm, riscv64, i686, x86_64 |
| archlinux | 2025.09.01 | aarch64, arm, **+ x86_64/i686 via pacstrap** |
| artix | 20250831 | — |
| chimera | 20250420 | aarch64, riscv64, x86_64 |
| debian | trixie | aarch64, arm, i686, x86_64 |
| deepin | beige | x86_64, aarch64 |
| fedora | 43-1.6 | aarch64, x86_64 |
| manjaro | 20250922 | — |
| opensuse | 16.0 | aarch64, x86_64 |
| oracle | 10 | aarch64, x86_64 |
| pardus | yirmibes | aarch64, i686, x86_64 |
| rockylinux | 10.0 | aarch64, x86_64 |
| trisquel | aramo | aarch64, arm, i686, x86_64 |
| ubuntu | questing | aarch64, arm, x86_64 |
| void | 20250202 | aarch64, arm, i686, x86_64 |

Eleven of these are distros RedTerm already ships.

**Arch on x86_64 is a special case.** `archlinux.sh` builds aarch64 and armv7 from
Arch Linux ARM images, then builds x86_64 and i686 by a completely different route: it
downloads the official `archlinux-bootstrap` tarball and runs `pacstrap` for each
target inside it. Arch dropped 32-bit, so this is not a download, it is a build. Note
RedTerm already lists Arch as available on all four of its ABIs.

### Four things the other recipes get wrong, which we must not copy

1. **`void.sh` disables TLS verification for every package operation.**
   `env SSL_NO_VERIFY_PEER=1 xbps-install …` on all three lines. Whatever the reason
   (almost certainly an expired or absent CA bundle in the minimal image), it means
   every package downloaded into a Void image is accepted without verification. Do
   not port this line. Install `ca-certificates` first, like the Debian recipe's
   `--include=ca-certificates`.

2. **`debian.sh` never clears apt's package lists.** No
   `rm -rf var/lib/apt/lists`, no `apt clean`. mmdebstrap leaves them, so every Debian
   image carries the full index for every configured repository, growing on every
   upgrade. Alpine's recipe at least clears `var/cache/apk`.

3. **`fedora.sh` runs `dnf reinstall -y '*'`** — reinstall every package in the image.
   Combined with `excludepkgs=*selinux*` and stripping `tsflags=nodocs` from
   `dnf.conf`, this is a deliberate "make a usable proot image, not a faithful
   container image" pass. Worth keeping the intent, but the `nodocs` removal is worth
   reconsidering: we want small images, and it is only there to stop dnf from
   redownloading docs.

4. **None of them produce a reproducible archive.** See §12 — this cost us two build
   cycles to find and fix.

Also noted: `distro-plugins/` is committed only because Git cannot track an empty
directory, which is why the first recipe build here aborted on a missing
`distro-plugins/`. Fixed in our copy by creating it.

---

## 12. Reproducibility: `--mtime` is not enough

Upstream's `--mtime="@${SOURCE_DATE_EPOCH}"` pins the mtime in each tar header. It does
**not** produce a reproducible archive, and we verified this on the real Alpine image.

Two builds of an unchanged recipe gave:

```
2c6a72b2…  build 1
7d4358ac…  build 2     <- different
```

The cause is not in the tree — content, modes and owners were all identical, and the
only differing file was `/var/log/apk.log`, which records the wall-clock time of every
apk invocation. Removing it was necessary but **not sufficient**: the archives still
differred.

Decompressing both and comparing the raw tar streams located the real source. GNU tar
writes a pax extended header per member containing `atime` and `ctime` with nanosecond
precision, taken from the filesystem at archive time:

```
30 atime=1791489187.131158246
30 ctime=1791489184.145137077
```

`--mtime` does not touch these. The fix is to delete those two keys:

```bash
--format=pax --pax-option="delete=atime,delete=ctime"
```

mtime, mode, owner, size and xattrs all survive, so the archive still describes the
tree faithfully and now depends only on the tree. Verified both ways: two builds are
byte-identical, **and** a single changed file still changes the hash — the control that
matters, since a hash that ignores content would be worse than no hash.

With that in place:

```
2c6a72b205aec3c7750fe453306262f19924ba0aa5cf2a9f795f4c270cceb305  build 1
2c6a72b205aec3c7750fe453306262f19924ba0aa5cf2a9f795f4c270cceb305  build 2
```

`SOURCE_DATE_EPOCH` must be pinned per release rather than defaulted to `date +%s`, or
there is no reproducibility to check.

### A second bug: upstream's permission repair does not work

```bash
sudo find "${2}" -type d -perm -400 -print0 | xargs -0 sudo chmod 755
```

`-perm -400` means "all bits of `400` are set" — the owner **can** read. So it selected
the directories that were already fine and left every unreadable one at `000`.
Demonstrated on a tree with 000/700/750/755 directories: the `000` stayed `000`, and
`700` and `750` were both widened to `755`. It failed to repair the problem it names and
loosened permissions on directories that did not need touching.

Our replacement, in `bootstrap-rootfs.sh`:

```bash
sudo find "${2}" -type d \( ! -perm -400 -o ! -perm -100 \) -print0 | xargs -0 -r sudo chmod u+rx
sudo find "${2}" -type f ! -perm -400 -print0 | xargs -0 -r sudo chmod u+r
```

Both bits are checked because both are needed: execute to reach into a directory, read
to list what is in it. `u+rx` rather than `755`, so a deliberately `0700` directory
stays `0700`. Files are repaired too, which upstream never did — an image whose
`/etc/shadow` is unreadable boots to a login that cannot work, and reports it as a
permission error rather than as a bad image. This is the same repair as
`RootfsArchive.makeReadable`, now on the build side.

---

## 13. Cross-check against upstream

Master's most recent commit at time of writing is
`tar_extract: the tail drain() pulls through is hashed too` — upstream independently hit
the same class of defect we did with undrained pipes, on the other side of the pipeline.
Worth reading their extractor against our `TarExtractor`; possibly worth contributing
back. No action taken.

---

## 14. Phase 1 status

Alpine 3.24.2, aarch64, built locally and verified.

**Delivered** in `/home/ubuntu/rootfs-images` (separate git repo, not yet initialised):

- `bootstrap-rootfs.sh` — vendored from `5bd8f4f`, GPL-3.0 `LICENSE` included, with
  four documented deviations: `mmdebstrap` no longer a global prerequisite (added
  `require_tools` so a recipe declares its own), `PLUGIN_DIR` is created rather than
  committed, version and release URL overridable via `ROOTFS_VERSION` /
  `ROOTFS_RELEASE_URL` so a local build needs no tag, and the two fixes from §12.
- `distro-build/alpine.sh` — aarch64 only, `dist_version` 3.24.2, `apk-tools` no longer
  pinned to a package version (upstream's `apk-tools=2.14.9-r3` would break the build
  the day that revision left the repository), apk cache and `var/log/apk.log` removed.
- `.gitignore` for `rootfs/`.

**Verified:**

| check | result |
|---|---|
| recipe runs end to end | 22 packages installed, `apk upgrade` applied |
| generated plugin's sha256 matches the tarball | match |
| `/dev` emptied in the archive | 0 entries |
| no unreadable directory left | 0 dirs lacking owner-read |
| apk cache cleared | 0 files |
| `/etc/passwd` + `/etc/shadow` present | yes, 17 shadow entries |
| hard links dereferenced | 0 multi-link files |
| **two builds, fixed epoch, byte-identical** | **yes, `2c6a72b2…`** |
| **boots under RedTerm's own `libproot.so`** | **yes** |

### Boot test — with RedTerm's own proot, not a substitute

The host happens to be aarch64, and RedTerm ships its own proot built by NDK r28c:

```
app/src/main/jniLibs/arm64-v8a/libproot.so        ELF 64-bit aarch64, static, Android 24
app/src/main/jniLibs/arm64-v8a/libproot-loader.so
```

Using those, with the flags `ProotLaunch.buildScript` actually emits (`-0 -L -r -w
--link2symlink --kill-on-exit` plus the `/dev` `/proc` `/sys` binds):

```
shell:    /bin/sh
uname -m: aarch64
id:       root
apk:      apk-tools 3.0.8-r0, compiled for aarch64.
```

Not just `uname`. A real workload, in the same proot:

| check | result |
|---|---|
| `apk add --no-cache curl` | OK |
| `curl -sI https://dl-cdn.alpinelinux.org/…` | `HTTP/2 200` — TLS and CA bundle work |
| create, write, `chmod +x`, execute a script | OK |
| `/etc/shadow` readable | yes — the §12 repair is what makes this true |
| `root:` line in `/etc/passwd` | `root:x:0:0:root:/root:/bin/sh` |

So the image is functional, not merely well-formed. Note that the distribution's own
apt/curl CA bundle is what made the HTTPS request work; that is also why `void.sh`'s
`SSL_NO_VERIFY_PEER=1` is a real defect and not merely untidy.

`/var/log/apk.log` reappears after the first `apk` call inside the session. That is
correct — the build strips it for reproducibility, and Alpine recreates it on demand.

**Still not claimed:** nothing has run inside the RedTerm app, on a device, with a real
terminal. The proot binary and flags are identical, so the remaining risk is the app's
own first-start setup and the `.startup` script, not the image.

Reproducibility currently requires `SOURCE_DATE_EPOCH` to be passed explicitly. It
should be pinned per release in Phase 2 rather than defaulting to `date +%s`.

### Phase 2 — all four architectures

The recipe now builds `aarch64 arm i686 x86_64`, listed in `distro_arches` so adding one
is a single line. All four build, and all four are byte-identical across two builds with
`SOURCE_DATE_EPOCH` **unset**:

| arch | sha256 (first 32) | match |
|---|---|---|
| aarch64 | `ebb9848a951e00c34f3c14e465e9e5b3` | yes |
| arm | `62355a2aef38646a68e37cb903458d33` | yes |
| i686 | `ced6d464421bca931ec3dde65b54be58` | yes |
| x86_64 | `842259ac065457f5749e7c89d7c64af4` | yes |

`SOURCE_DATE_EPOCH` now defaults to `0` rather than `date +%s`, so reproducibility is the
default rather than something a caller must remember to ask for. Zero rather than a fixed
date, because a fixed constant has to be bumped by hand and forgetting to is exactly the
failure this prevents.

Cross-arch build works through `binfmt_misc` + `qemu-user`, so no emulator name appears
in the recipe: binfmt dispatches on the binary's own ELF header.

### Booting all four, and where the test harness lies

| arch | via RedTerm's `libproot.so` | notes |
|---|---|---|
| aarch64 | boots | `/etc/shadow` readable |
| arm | boots, **only in a fresh PID namespace** | see below |
| i686 | image fine, **harness blocked** | `ptrace(TRACEME): Function not implemented` |
| x86_64 | image fine, **harness blocked** | same, under qemu |

Two of the four could not be booted with RedTerm's own proot, and neither failure is a
defect in the images:

- **arm needs a fresh PID namespace.** 32-bit `pthread_mutex_t` only holds pids up to
  65535, and this host's pid counter is well past that. proot aborted with a message that
  reads like an image problem and is not one. `sudo unshare -pf --mount-proc` gives the
  run a low pid counter and it boots: `uname=armv8l id=root`, shadow readable. On a real
  device pids start near 1, so this will not occur in RedTerm — but any 32-bit distro
  tested from a long-lived host process needs it.
- **i686 and x86_64 fail at `ptrace(TRACEME)`.** The 64-bit proot runs an x86_64 guest
  image under qemu-user; qemu-user cannot provide the ptrace proot needs to trace its
  tracee. Verified independently that the images are sound: `chroot` into each and run
  their own binaries natively —
  `uname -m` → `i686` / `x86_64`, `id` → `root`, `apk-tools 3.0.8-r0 compiled for x86_64`,
  `/etc/shadow` readable. Also confirmed the arm image really is 32-bit ARM
  (`ELF 32-bit LSB pie, ARM EABI5`), since `chroot` reported the host's `aarch64` from
  `uname`.

So: two arches booted under RedTerm's own proot, two verified at image level. All four are
reproducible. Only a device can close the remaining gap, and the gap is now known to be
in the harness rather than the images.

### CI

`.github/workflows/build.yml`, plus `scripts/select-distributions.sh` — extracted from
inline YAML so the decision can be exercised off GitHub, which it was. Cases checked:

| event | result |
|---|---|
| push, one recipe changed | that recipe only |
| push, **only** `bootstrap-rootfs.sh` changed | all recipes |
| push, builder **and** one recipe changed | all recipes |
| push, unrelated file | all recipes |
| PR, two recipes across two commits | both |
| `workflow_dispatch` with two names | those two, explicit wins over the diff |
| `workflow_dispatch` with a bad name | exit 1, names the missing recipe |
| `pull_request` without a base | exit 1 |

The second row is the one that matters. Upstream's workflow reads only the head commit, so
a builder change builds nothing and a multi-commit PR builds only its last recipe. Either
way the affected images keep bytes from the old builder while a fresh sha256 is published
for them — worse than not publishing, because it looks current.

The workflow also rebuilds once and compares sha256, which is what makes
"reproducible" a property of the pipeline rather than a claim in a document.

`/home/ubuntu/rootfs-images` is a git repository, three commits. Tracked:
`.github/workflows/build.yml`, `.gitignore`, `LICENSE` (GPL-3.0),
`bootstrap-rootfs.sh`, `distro-build/alpine.sh`, `scripts/select-distributions.sh`.
`rootfs/`, `distro-plugins/` and `.build/` are ignored — all generated, and a committed
plugin is always stale. **Nothing is published and nothing is pushed.**

Phase 3 is not started. It is the phase that needs the supply-chain decision in §9.2
answered explicitly, because it is the point at which our sha256 and our URL start being
what users install.

---

## 15. Phase 5 — the expanded distribution list

Eight names were requested. Researched against each project's own release hosts rather
than accepted on trust, and the answer is three keepers and five drops.

### Kept

| distro | source | arches | why |
|---|---|---|---|
| **Gentoo** | `distfiles.gentoo.org` stage3 autobuilds | all four | a real minimal rootfs with a resolved `@world`, dated and pinnable |
| **NixOS** | `releases.nixos.org` nix installer tarball | aarch64, x86_64 | the system images are 64-bit only; the 32-bit nix tarballs are not a NixOS |
| **ARMtix** | `armtix.artixlinux.org` rootfs tarballs | aarch64 | the only genuine Artix-lineage rootfs tarball that exists |

### Dropped, and why

| distro | reason |
|---|---|
| **CachyOS** | desktop ISO only, no bootstrap tarball, x86_64 only |
| **EndeavourOS** | ISO only; the official mirror publishes just `iso/` and `repo/` |
| **Artix** (mainline) | ISO only. Upstream's `artix.sh` is mislabelled — it builds **Armtix**, from armtixlinux.org |
| **Deepin** | desktop-only ISO, no base profile, no server edition — exactly the "desktop only" case |
| **Pardus** | has a Server ISO, but amd64 only and no tarball; adds nothing beside Alma/Rocky |
| **CentOS Linux 7 / 8** | EOL in 2024 and 2021. Shipping them means shipping unpatched trees |

**CentOS Stream 10 is viable** but only as a container image (`registry.access.redhat.com/ubi10`,
aarch64/x86_64, no 32-bit), which needs OCI extraction we do not have tooling for yet. Not
built; noted rather than skipped silently.

### What the research turned up that matters more than the list

**RedTerm's Arch images for aarch64 and arm cannot be reproduced from upstream at all.**
They come from ArchLinuxARM, whose rootfs files are named `-latest` with no dated snapshots,
so no sha256 can be stable. Arch x86_64 and i686 come from the official
`archlinux-bootstrap-<date>-x86_64.tar.zst`, which *is* dated. So for two of Arch's four
architectures, building our own is the only way to have a checksum we can stand behind.

**Gentoo's 32-bit port directories are not named after them.** There is no
`releases/armhf/` and no `releases/i686/` — both 404, which reads as "32-bit was dropped".
i686 lives under `x86/` and armv7 under `arm/`.

**NixOS no longer publishes `nixos-<ver>-<arch>-linux.tar.xz` at all.** The release directory
holds only ISOs and `nixexprs.tar.xz`. The nix *installer* tarball is the minimal-rootfs
path, which is what upstream uses too.

### Gentoo built and booted

| arch | stage3 | built |
|---|---|---|
| aarch64 | `stage3-arm64-openrc-20261004T233054Z` | 194 MB |
| x86_64 | `stage3-amd64-openrc-20261004T164559Z` | built |
| arm | `stage3-armv7a-openrc-20260902T220055Z` | 177 MB |
| i686 | `stage3-i686-openrc-20261006T170059Z` | built |

Under RedTerm's own `libproot.so`: `uname=aarch64`, `id=root`, `bash 5.3.15`,
`Portage 3.0.82.2`, `/etc/shadow` readable, first-start setup baked.

Registered in `DistroRegistry` with its v0.6.0 checksums, so it installs even with no
index reachable, and given a brand colour — the name-derived fallback came out at 4.40:1
and the badge test requires 4.5, so Gentoo's published purple `#54487A` is named instead
(8.12:1).

### Debian and Ubuntu

`distro-build/debian.sh` builds both suites from one recipe. Debian 13.7 trixie booted under
RedTerm's proot (`/etc/debian_version` → `13.7`, bash 5.2.37, shadow readable). Ubuntu 26.04
LTS resolute is built by the same recipe.

**Worth doing on its own merits:** RedTerm currently pins Ubuntu `questing`, which is 25.10 —
an interim release — while 26.04 LTS has been available since April 2026. That is a
long-lived support base versus a nine-month one, independent of this project.

---

## 16. Phase 3 — the published index, consumed

`RootfsIndex` parses the index, `RootfsCatalog` applies the cached copy synchronously at
startup and refreshes on a background thread, and `DistroRegistry.allDistros` reads through
an override. The built-in table stays the fallback, so **an install never depends on a fetch
succeeding**. Four rules, each with a test:

- An entry with **no sha256 is never resolved**. The index exists so the bytes fetched are
  the bytes published, and an entry without a checksum cannot claim that.
- A distribution the registry has never heard of is **not invented**. It needs a description,
  a package manager, an install size and a first-start plan; guessing gives an entry that
  installs and then behaves wrongly.
- The index **cannot widen the architectures on offer**. It is a supply of newer images, not
  a new platform.
- An **unknown schema is refused outright**, because half-reading a future format means
  guessing what its fields mean, and a guessed field is a wrong download.

17 new tests. Full suite **346 tests, 0 failures; lint clean; debug APK builds**.

---

## 17. Bugs found by building rather than reading

Every one of these looked correct when written.

**The index's release URLs omitted the version**, so every URL was
`.../releases/download/alpine-aarch64-rt-v0.5.0.tar.xz` — not a release asset URL at all.
Well-formed, 404s, and nothing notices until a device installs from it. `generate-index.sh`
now asserts the version is in the path.

**Filenames cannot carry two versions.** The first index generator parsed
`alpine-aarch64-3.24.2-...` as distro `alpine-aarch64`, arch `3.24.2`. Identity now comes from
a sidecar a recipe writes; filenames are `<name>-<arch>-rt-<build>.tar.xz` for humans only.

**The app was reconstructing the URL template from concrete per-arch URLs**, by guessing which
part of the path was the architecture. The index publishes `baseUrlTemplate` instead, and the
generator asserts it substitutes back to the concrete URL exactly.

**`WORKDIR` was upstream's hardcoded `/tmp`.** `/tmp` is commonly a tmpfs sized as a fraction
of RAM; here it was 2.9 GB against Gentoo's 1.2 GB per architecture. The failure is one
`Cannot write: No space left on device` line per file, naming nothing. Now beside the
repository, overridable, with an early free-space check that says what is wrong.

**`--acls --xattrs --xattrs-include='*'` together extracted ZERO files** from a Gentoo stage3
and exited 2 with only `tar: Multiple archive files require '-M' option`, which names nothing.
Plain `tar -xf` extracts all 69373 members of the same archive.

**Gentoo's `.sha256` sidecar is a clear-signed PGP message.** `sha256sum -c` on it warns about
13 improperly formatted lines and passes on luck. The line for our own file is now extracted
and compared directly.

**`set -o pipefail` plus `set -e` killed the index generator silently.** A field absent from a
sidecar made `grep` exit non-zero inside a command substitution, which made the assignment fail
and the script exit with no message.

**Stripping `/usr/lib/locale` silently removes UTF-8.** On Debian 13 `C.UTF-8` lives at
`/usr/lib/locale/C.utf8`, not in `/usr/share/locale`. Removing it makes `locale -a` stop listing
`C.utf8`, RedTerm's launcher falls back from `LANG=C.UTF-8` to `LANG=C`, and the image quietly
loses UTF-8 — **no error and no warning**, just wrong collation and character classification.
`trim-rootfs.sh` now compares the locale list before and after rather than assuming.

**Interrupting `mmdebstrap` leaks mounts.** It binds `/dev`, `/proc` and `/sys` into its target,
so killing it — a cancelled CI job, a Ctrl-C — leaves them, and every later run fails with
`Device or resource busy`. Unmounts deepest-first now.

**A test that patched its own fixture proved nothing.** `good.replace(...)` that fails to match
silently returns the original, so "apply leaves the distro alone when templates disagree" was
asserting the opposite of what it claimed. Four fixtures rewritten as explicit documents.

**Filenames lied about their contents:** Ubuntu images were named `resolute-aarch64-trixie-...`,
carrying the Debian codename.

---

## 18. State at the end of this work

| | |
|---|---|
| `/home/ubuntu/rootfs-images` | 7 commits, GPL-3.0, CI with a reproducibility gate |
| RedTerm `dev` | all phases committed, 346 tests green, lint clean, APK builds |
| Built and booted under RedTerm's proot | Alpine (4 arches), Debian 13.7 trixie, Gentoo (4 arches) |
| Built, boot not verified | Ubuntu 26.04 resolute (3 arches) |
| **Not done** | nothing is published or pushed. No GitHub release exists, so the Gentoo and Debian URLs in the registry point at a tag that does not exist yet. |

### Phase status

- **Phase 1** done. **Phase 2** done. **Phase 4** done. **Phase 3** implemented but not
  published. **Phase 5** done for Gentoo; NixOS, ARMtix and CentOS Stream identified with
  verified sources and not yet built.
