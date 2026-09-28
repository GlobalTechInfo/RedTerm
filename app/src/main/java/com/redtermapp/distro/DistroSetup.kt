package com.redtermapp.distro

/**
 * Builds the `/root/.startup` script that a freshly extracted rootfs runs on
 * first launch. Kept separate from the activity so the generated shell can be
 * reviewed and syntax-checked in isolation.
 */
object DistroSetup {

    const val MARKER = "# redterm-startup v5"

    /** Every key plan() can return. Anything else gets the empty fallback. */
    val KNOWN = setOf(
        "alpine", "debian", "ubuntu", "kali", "void", "fedora", "rocky", "almalinux",
        "arch", "manjaro", "opensuse"
    )

    /**
     * Maps an /etc/os-release ID onto a plan() key, or null when it is not one we
     * handle. This has to exist because distributions do not use our names: Arch Linux
     * ARM reports "archlinuxarm" and openSUSE Leap reports "opensuse-leap", and an
     * unrecognised ID used to fall through to the empty plan, which silently produced
     * a startup script that did nothing at all.
     */
    fun canonicalize(id: String): String? {
        val key = id.trim().lowercase()
        if (key.isEmpty()) return null
        if (key in KNOWN) return key
        return when (key) {
            "archlinuxarm", "archarm", "arch-linux-arm", "arch linux arm" -> "arch"
            "opensuse-leap", "opensuse-tumbleweed", "opensuse-microos",
            "suse", "sles", "sled", "opensuse" -> "opensuse"
            "almalinux", "alma" -> "almalinux"
            "rocky", "rockylinux" -> "rocky"
            "manjaro-arm" -> "manjaro"
            "void" -> "void"
            "kali-linux" -> "kali"
            else -> if (key.startsWith("debian")) "debian" else null
        }
    }


    private val pacmanXfer =
        "sed -i '/^[[:space:]]*XferCommand/d' /etc/pacman.conf; " +
            // %o is the destination file and %u the URL. pacman substitutes these
            // itself and does not append the URL, so an XferCommand without %u
            // runs curl with flags and no URL at all: "curl: (2) no URL specified",
            // repeated once per repository. The stock comment in pacman.conf shows
            // the same shape.
            // No --speed-limit/--speed-time: those make curl *abort* a transfer that
            // drops below the threshold instead of failing over, which on a mobile
            // link killed downloads that were still progressing ("Operation too slow.
            // Less than 1 bytes/sec transferred the last 10 seconds"). Retries and a
            // connect timeout are kept, since those do fail over to the next mirror.
            // -C - is deliberately absent. It makes curl send Range requests on retry,
            // and this mirror answers those with 404; combined with -f that is exit 22,
            // so pacman reports "failed to download" for files that were in fact fully
            // transferred (24.7 MiB at 16.9 MiB/s, then five 404s). Resume buys nothing
            // for a fresh package fetch.
            // The `a text` one-liner this used is a GNU extension; busybox sed does not
            // implement it, so the insert silently failed and pacman fell back to its
            // own downloader. A substitution is portable, and the stock pacman.conf
            // always carries a commented example inside [options] to replace. The grep
            // afterwards makes a failure visible instead of silent, which is what hid
            // this for so long.
            "sed -i 's|^[#[:space:]]*XferCommand.*|" +
            // Progress is deliberately left visible. A silent download on a phone gives
            // no indication that anything is happening, and a slow link makes a healthy
            // transfer look like a hang. Errors still stand out because they go to
            // stderr. Use --progress-bar for a single clean line per file.
            "XferCommand = /usr/bin/curl -fL --progress-bar --retry 3 --retry-delay 2 " +
            "--connect-timeout 15 -o %o %u|' /etc/pacman.conf; " +
            "grep -q '^XferCommand' /etc/pacman.conf || " +
            "echo '>>> Warning: could not set the pacman download command.'; " +
            ""

    // Manjaro shares this; the arm mirrorlist it ships is left untouched.

    // xbps writes every download into /var/cache/xbps and reads it back from
    // there. A rootfs without those directories makes the fetch fail with "No such
    // file or directory", and the partial file it left behind then reads back as a
    // truncated archive, which is what "Truncated tar archive detected" and
    // "failed to read index" mean. Cheapest to guarantee the directories exist.
    private val xbpsDirs =
        "mkdir -p /var/cache/xbps /var/db/xbps /var/log"

    // systemd's postinst runs systemd-machine-id-setup, which asks the system bus
    // for an id. proot has no D-Bus, so that fails with "Protocol driver not
    // attached", dpkg leaves systemd half-configured and takes systemd-sysv down
    // with it, and apt reports errors for the whole upgrade. Seeding a random id
    // makes the tool short-circuit. It is per-install and nothing on this device
    // talks to a system bus, so a stable value is fine.
    private val machineId =
        "if [ ! -s /etc/machine-id ]; then " +
            "mkdir -p /etc /var/lib/dbus; " +
            "id=\$(cat /proc/sys/kernel/random/uuid 2>/dev/null | tr -d '-'); " +
            "[ -n \"\$id\" ] || id=redterm0123456789abcdef01234567; " +
            "printf '%s' \"\$id\" > /etc/machine-id; " +
            "cp /etc/machine-id /var/lib/dbus/machine-id 2>/dev/null || true; " +
            "fi"

    // Arch split gcc-libs into libgcc and libstdc++, so an older rootfs still owns
    // /usr/lib/libgcc_s.so.1 and libstdc++.so.6 from gcc-libs and every install of
    // the new packages aborts with "failed to commit transaction (conflicting
    // files)". This has to run BEFORE any upgrade, and on every session rather than
    // once: it previously sat after the upgrade, so the upgrade failed first and the
    // marker was written anyway, leaving the conflict in place permanently. The
    // pacman -Qq guard makes it a no-op once the split packages are installed.
    private val gccLibsRepair =
        // Arch split gcc-libs into libgcc and libstdc++. An older rootfs still owns
        // those files through gcc-libs, so installing anything that pulls in libstdc++
        // fails with "failed to commit transaction (conflicting files)".
        //
        // Neither ordering works on its own. Installing the split packages first is
        // rejected because pacman checks file conflicts before unpacking anything, and
        // removing gcc-libs first is worse: it owns the libstdc++.so.6 that pacman
        // itself is linked against, so deleting it can leave pacman unable to start.
        // --overwrite resolves it in a single transaction, taking ownership of the
        // files as the replacements are unpacked. The check is purely local and
        // idempotent, so this is safe on every session.
        "if pacman -Qq gcc-libs >/dev/null 2>&1; then " +
            "pacman -S --noconfirm --needed --overwrite '/usr/lib/libgcc*' " +
            "--overwrite '/usr/lib/libstdc++*' " +
            "--overwrite '/usr/share/locale/*/LC_MESSAGES/libstdc++*' " +
            "libgcc libstdc++ >/dev/null 2>&1 || " +
            "echo \'>>> Warning: could not clear the gcc-libs conflict.\'; fi; "

    // Manjaro's image already ships a working mirrorlist (its generator emits a live
    // Server line, e.g. ftp.psnc.pl .../arm-stable/$repo/$arch), so this only makes
    // sure the file exists. An earlier version overwrote it, discarding the image's
    // own mirror, and a later one appended unsynchronised fallbacks. Leave it alone.
    private val manjaroMirrors =
        "mkdir -p /etc/pacman.d; " +
            "touch /etc/pacman.d/mirrorlist; " +
            // Manjaro is aarch64-only here and serves ARM from arm-stable, laid out as
            // arm-stable/$repo/$arch. Both hosts were checked for core.db and extra.db
            // before being used.

            "$pacmanXfer"

    // Kept exactly as it was: Fedora worked with this, so it is not being changed.
    private val disableRepos =
        "for f in /etc/yum.repos.d/*.repo; do [ -e \"\$f\" ] && mv \"\$f\" \"\$f.redterm-off\"; done; "

    // Rocky and AlmaLinux needed more than that. Both were resolving a
    // "Fedora $releasever" repository instead of their own, and the narrow version
    // above cannot see it: it misses the older /etc/yum/repos.d path and any
    // repository declared inside /etc/dnf/dnf.conf. This disables every directory
    // dnf reads and replaces dnf.conf, and is deliberately used only for the two
    // distros that need it so working distros keep their existing behaviour.
    private val disableReposThorough =
        "mkdir -p /etc/yum.repos.d /etc/yum/repos.d; " +
            "for d in /etc/yum.repos.d /etc/yum/repos.d; do " +
            "[ -d \"\$d\" ] || continue; " +
            "for f in \"\$d\"/*.repo \"\$d\"/*.conf; do " +
            "[ -e \"\$f\" ] && mv \"\$f\" \"\$f.redterm-off\"; " +
            "done; done; " +
            "if [ -f /etc/dnf/dnf.conf ] && [ ! -f /etc/dnf/dnf.conf.redterm-off ]; then " +
            "cp /etc/dnf/dnf.conf /etc/dnf/dnf.conf.redterm-off; " +
            "fi; " +
            "mkdir -p /etc/dnf; " +
            "printf '%s\\n' '[main]' 'gpgcheck=1' 'installonly_limit=3' " +
            "'clean_requirements_on_remove=True' 'best=True' 'skip_if_unavailable=False' " +
            "> /etc/dnf/dnf.conf; "

    // Nothing is appended any more. Extra failovers turned out to be harmful: the
    // Arch Linux ARM frontends are not synchronised with each other, so pacman
    // fetched a database from one and packages from another and every package 404'd
    // (the same install listed git-2.55.0-1 and then git-2.52.0-2). The image's own
    // mirrorlist is a single, self-consistent source, so it is left entirely alone.
    // The architecture only decides which project the image already points at:
    //   aarch64 / armv7h -> Arch Linux ARM, flat: $arch/$repo/$repo.db
    //   i686              -> Arch Linux 32, also flat: $arch/$repo/$repo.db
    //   x86_64            -> official Arch, $repo/os/$arch
    // The append is guarded so it is idempotent: the prepare step runs every session.
    //
    // An earlier version rewrote the whole file with [aarch64]/[i686]/[core] headers,
    // which broke pacman badly. A section header in a mirrorlist is a *repository
    // filter*, not a tag, so those names were treated as repositories of their own:
    // "could not register 'aarch64' database (database already registered)" for each
    // header, then "failed to synchronize all databases (no servers configured for
    // repository)". Plain "Server = " lines only.
    private val archMirrors =
        "mkdir -p /etc/pacman.d; " +
            "touch /etc/pacman.d/mirrorlist; " +

            "arch=\$(uname -m); " +
            "case \"\$arch\" in " +
            // arm images already point at mirror.archlinuxarm.org, which is in sync with
            // itself, so nothing is added. An empty branch body is a syntax error, so it
            // is explicitly a no-op.
            "aarch64|armv7l) : ;; " +
            "x86_64) " +
            "grep -q 'mirrors.kernel.org/archlinux' /etc/pacman.d/mirrorlist || " +
            "printf '%s\\n' " +
            "'Server = https://mirrors.kernel.org/archlinux/\$repo/os/\$arch' " +
            ">> /etc/pacman.d/mirrorlist ;; " +
            "i686) " +
            "grep -q 'mirror.archlinux32.org' /etc/pacman.d/mirrorlist || " +
            "printf '%s\\n' " +
            "'Server = https://mirror.archlinux32.org/\$arch/\$repo' " +
            ">> /etc/pacman.d/mirrorlist ;; " +
            "esac; " +
            "$pacmanXfer"


    // dnf's official metalink hands back stale mirrors (mirrors.qlu.edu.cn among
    // them) that 404 on aarch64 and spam the log on every command. Point the
    // repos we actually need at a single verified mirror instead. The file is named
    // after the vendor it configures, so the repository in use is readable at a
    // glance from `ls /etc/yum.repos.d`.
    private fun fedoraRepos() =
        rhelStyleRepos(
            disable = disableRepos,
            file = "fedora.repo",
            // id, display name, path segment on the mirror
            sections = listOf(
                Triple("fedora", "name=Fedora \$releasever - \$basearch", "Everything"),
                Triple(
                    "updates",
                    "name=Fedora \$releasever - \$basearch - Updates",
                    "Everything"
                )
            ),
            baseUrl = { id, seg ->
                if (id == "updates") {
                    "https://mirrors.kernel.org/fedora/updates/\$releasever/$seg/\$basearch/"
                } else {
                    "https://mirrors.kernel.org/fedora/releases/\$releasever/$seg/\$basearch/os/"
                }
            }
        )

    // Each vendor's own servers, in a file named after that vendor. RedTerm hosts
    // nothing: the file is only the configuration we generate.
    private fun rhelRepos(host: String, label: String, file: String) =
        rhelStyleRepos(
            disable = disableReposThorough,
            file = file,
            // The path segments are spelled out rather than derived. Deriving them
            // with capitalize() produced "Baseos" and "Appstream", and both of those
            // 404: the directories are "BaseOS" and "AppStream". Only running the
            // generated script against a real directory tree showed that; the
            // strings all looked plausible.
            sections = listOf(
                Triple("baseos", "name=$label \$releasever - \$basearch - BaseOS", "BaseOS"),
                Triple(
                    "appstream",
                    "name=$label \$releasever - \$basearch - AppStream",
                    "AppStream"
                )
            ),
            baseUrl = { _, seg -> "$host/\$releasever/$seg/\$basearch/os/" }
        )

    private fun rhelStyleRepos(
        disable: String,
        file: String,
        sections: List<Triple<String, String, String>>,
        baseUrl: (String, String) -> String
    ): String = buildString {
        append(disable)
        append("printf '%s\\n'")
        for ((id, name, segment) in sections) {
            append(" '[")
            append(id)
            append("]' '")
            append(name)
            append("' 'baseurl=")
            append(baseUrl(id, segment))
            append("' 'gpgcheck=1' 'enabled=1'")
            if (id == sections.first().first) append(" ''")
        }
        append(" > /etc/yum.repos.d/")
        append(file)
        append("; ")
        // Belt and braces: after writing our own file, delete any other enabled repo
        // definition. A leftover fedora.repo kept winning on Rocky and AlmaLinux even
        // though the vendor's own file was written, so ownership of the directory is
        // now asserted rather than assumed. Files already parked as *.redterm-off are
        // left alone, and so is anything in the legacy /etc/yum/repos.d.
        append("find /etc/yum.repos.d -maxdepth 1 -name '*.repo' ")
        append("! -name '")
        append(file)
        append("' ! -name '*.redterm-off' -delete 2>/dev/null; ")
    }

    // Only the solv *index* is discarded, never /var/cache/zypp as a whole: zypper
    // writes <repo>/solv as a file, so a stale directory left there by the earlier
    // `chmod -R 755` makes it fail with "Can't open solv-file:
    // /var/cache/zypp/solv/openSUSE:repo-oss/solv". Clearing the index alone rebuilds
    // it from the raw metadata that is already on disk, so nothing is re-downloaded.
    // Removing the broken repos is cheap and idempotent, so it is re-applied on every
    // start. The cache directories are created but never deleted: an earlier version
    // did `rm -rf /var/cache/zypp` plus `chmod -R 755`, which forced zypper to
    // re-download every repository on each session (over an hour) and left it unable
    // to write its solv cache ("Can't open solv-file: .../solv"). Recreating the
    // directories is what actually repairs that, and zypper manages the rest.
    private val opensuseClean =
        // libzypp fails with "Can't open solv-file:
        // /var/cache/zypp/solv/openSUSE:repo-oss/solv" because the repository alias
        // contains a colon. The alias is the .repo file's *section header*
        // ([openSUSE:repo-oss]); the name= line is only a display label.
        //
        // Three things have to change, not one:
        //  1. the section header, or the alias keeps the colon;
        //  2. the filename, which is openSUSE:repo-oss.repo and is what anything
        //     falling back to the basename would use;
        //  3. /var/lib/zypp/RepoManager.db, which caches the parsed repo list. Even
        //     with the files rewritten, a stale RepoManager.db keeps serving the old
        //     aliases, which is the most likely reason the rewrite appeared to do
        //     nothing. Deleting it is safe: zypper rebuilds it on the next refresh.
            "for d in /etc/zypp/repos.d /usr/share/zypp/repos.d; do " +
            "[ -d \"\$d\" ] || continue; " +
            "for r in \"\$d\"/*.repo; do " +
            "[ -e \"\$r\" ] || continue; " +
            "sed -i -e 's/^\\[[^:]*:\\(.*\\)\\]\$/[\\1]/' \"\$r\" || " +
            "echo '>>> Warning: could not rewrite a zypp repository alias.'; " +
            "b=\$(basename \"\$r\"); " +
            "case \"\$b\" in *:*) mv \"\$r\" \"\$d/\${b#*:}\" ;; esac; " +
            "done; done; " +
            "rm -f /etc/zypp/repos.d/*repo-openh264* /etc/zypp/repos.d/*-source.repo " +
            "/etc/zypp/repos.d/*-debug.repo /usr/share/zypp/repos.d/*repo-openh264* " +
            "/usr/share/zypp/repos.d/*-source.repo /usr/share/zypp/repos.d/*-debug.repo " +
            "/var/lib/zypp/RepoManager.db; " +
            // zypp creates these read-only, so rm fails with "Operation not permitted"
            // and the stale raw cache (which still names the old colon aliases) survives.
            "chmod -R u+w /var/cache/zypp 2>/dev/null; " +
            "rm -rf /var/cache/zypp/solv /var/cache/zypp/raw; " +
            "mkdir -p /var/cache/zypp/solv /var/cache/zypp/raw /var/cache/zypp/packages"

    // A crashed or segfaulted apt/dpkg leaves its lock files behind and every later
    // apt call then fails with "Could not get lock /var/lib/dpkg/lock". The lock is
    // only removed when no apt or dpkg process is actually running, so a genuinely
    // busy system is left alone.
    private val staleLockRepair =
        "    for _l in /var/lib/apt/lists/lock /var/lib/dpkg/lock /var/lib/dpkg/lock-frontend " +
            "/var/cache/apt/archives/lock; do\n" +
            "        [ -e \"\$_l\" ] || continue\n" +
            "        _busy=0\n" +
            "        for _p in /proc/[0-9]*; do\n" +
            "            _c=\$(cat \"\$_p/comm\" 2>/dev/null) || continue\n" +
            "            case \"\$_c\" in apt|apt-get|dpkg|dpkg-deb|unattended*) _busy=1; break ;; esac\n" +
            "        done\n" +
            "        if [ \"\$_busy\" = 0 ]; then rm -f \"\$_l\"; fi\n" +
            "    done\n"

    // The guard keeps a first-start upgrade from running while the very shell that is
    // running it is being replaced underneath itself. It is retained only for the
    // distros that were already working, where changing this is out of scope. Arch and
    // Manjaro must NOT be guarded: there the update is the whole point, and hiding it
    // behind a bash check is what made setup report success without doing anything.
    private const val GUARD_OPEN = "if ! command -v bash >/dev/null 2>&1; then "
    private const val GUARD_CLOSE = "; fi"

    private fun guarded(cmd: String) = if (cmd.isEmpty()) "" else GUARD_OPEN + cmd + GUARD_CLOSE

    private fun line(cmd: String) = if (cmd.isEmpty()) "" else "    $cmd\n"

    // install, quiet, prepare, repair, update
    private fun plan(distro: String): List<String> = when (distro) {
        "alpine" -> listOf("apk add", "-q", "", "", guarded("apk update"))
        "debian", "ubuntu", "kali" -> listOf(
            "DEBIAN_FRONTEND=noninteractive apt-get install -y", "-qq", machineId, "",
            guarded("apt-get update")
        )
        "fedora" -> listOf("dnf install -y", "-q", fedoraRepos(), "", "")
        "rocky" -> listOf(
            "dnf install -y", "-q",
            rhelRepos("https://dl.rockylinux.org/pub/rocky", "Rocky Linux", "rocky.repo"), "", ""
        )
        "almalinux" -> listOf(
            "dnf install -y", "-q",
            rhelRepos("https://repo.almalinux.org/almalinux", "AlmaLinux", "almalinux.repo"), "", ""
        )
        "void" -> listOf("xbps-install -S", "", xbpsDirs, "", guarded("xbps-install -Su"))
        // The image is a snapshot: glibc and the gcc-libs/libgcc split are both
        // behind the repos, so a full upgrade is the only way `pacman -S` keeps
        // working. It is also the only distro that needs the network on first
        // boot; every image already ships bash.
        // The upgrade has to be able to replace the shell that is running it. Upgrading
        // glibc and bash in place killed the live /bin/bash and the startup script died
        // at its final `exec bash -i`, which is what produced "/root/.startup[31]:
        // /bin/bash: No such file or directory". So the whole first-start block runs in a
        // subshell, and the login shell is then started fresh in a new process: the
        // upgrade may replace bash, but nothing that is executing depends on the old
        // binary still being on disk.
        "arch" -> listOf(
            "pacman -S --noconfirm --needed", "", archMirrors, gccLibsRepair,
            "( pacman -Syy --noconfirm && pacman -Syu --noconfirm )"
        )
        "manjaro" -> listOf(
            "pacman -S --noconfirm --needed", "", manjaroMirrors + gccLibsRepair, "",
            "pacman -Syy --noconfirm && pacman -Syu --noconfirm"
        )
        "opensuse" -> listOf(
            "zypper --non-interactive install -y", "", opensuseClean, "",
            "zypper --non-interactive refresh"
        )
        else -> listOf(":", "", "", "", "")
    }

    private val bashInstallFor = { install: String, quiet: String ->
        "    if ! command -v bash >/dev/null 2>&1; then " +
            "$install $quiet bash || echo '>>> Warning: could not install bash; the shell is unavailable.'; " +
            "fi\n"
    }

    // proot already runs as root inside the distro, so a real sudo package buys
    // nothing. A tiny shim keeps `sudo <cmd>` working without dragging the
    // package manager into first boot.
    // Overwrites the real sudo: it relies on setuid/setgroups behaviour proot
    // cannot emulate and segfaults, and proot already runs as root, so the
    // package buys nothing.
    private const val SUDO_SHIM =
        "    printf '%s\\n' '#!/bin/sh' " +
            "'# RedTerm: proot already runs as root, so sudo just runs the command.' " +
            "'while [ \$# -gt 0 ]; do case \"\$1\" in -*) shift ;; *) break ;; esac; done' " +
            "'if [ \$# -eq 0 ]; then exec /bin/sh; fi' 'exec \"\$@\"' " +
            "> /usr/bin/sudo; chmod 755 /usr/bin/sudo\n"

    fun buildStartupScript(distro: String): String {
        val p = plan(distro)
        return buildString {
            append(MARKER).append('\n')
            // Deliberately outside the one-shot block. Repository configuration is
            // cheap and idempotent, so it is re-applied on every start. Keeping it
            // inside meant a first run that failed to reach its mirrors wrote a
            // broken mirrorlist, still touched /root/.init_done because bash was
            // present, and so never repaired itself: pacman stayed broken forever
            // while the terminal cheerfully printed "Setup complete".
            append(line(p[2]))
            append("if [ ! -f /root/.init_done ]; then\n")
            append("    echo '>>> First-time distro setup...'\n")
            // The update's exit status decides whether setup counts as done. It used
            // to be ignored: a failed `pacman -Syyu` still wrote /root/.init_done and
            // still printed "Setup complete.", so the rootfs was left with a database
            // and package set that did not match, every install then failed with
            // "conflicting files", and nothing ever retried. Failing now says so and
            // leaves the marker alone so the next start tries again.
            if (p[4].isEmpty()) {
                append("    update_ok=1\n")
            } else {
                append("    update_ok=0\n")
                append(line(p[4]).replaceFirst("\n", " && update_ok=1\n"))
            }
            append(line(p[3]))
            append(bashInstallFor(p[0], p[1]))
            append(SUDO_SHIM)
            append("    if [ \"\$update_ok\" = 0 ]; then\n")
            append("        echo '>>> System update failed; setup will retry on the next start.'\n")
            append("    elif command -v bash >/dev/null 2>&1; then\n")
            append("        touch /root/.init_done\n")
            append("        echo '>>> Setup complete.'\n")
            append("    else\n")
            append("        echo '>>> bash is still unavailable; retrying on next terminal start.'\n")
            append("    fi\n")
            append("else\n")
            append("    echo '>>> Setup already complete; skipping the system update.'\n")
            append("fi\n")
            append("if ! command -v bash >/dev/null 2>&1; then\n")
            append("    echo '>>> Repairing missing bash...'\n")
            append("    ${p[0]} ${p[1]} bash || echo '>>> bash unavailable; falling back to /bin/sh.'\n")
            append("fi\n")
            append(staleLockRepair)
            append("unset ENV\n")
            append("if command -v bash >/dev/null 2>&1; then\n")
            append("    exec bash -i\n")
            append("fi\n")
            append("exec /bin/sh -i\n")
        }
    }
}
