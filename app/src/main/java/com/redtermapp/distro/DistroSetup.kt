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
        "arch", "armtix", "manjaro", "opensuse", "oracle",
        "pardus", "parrot", "trisquel", "slackware"
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
            // Leap and Tumbleweed report "opensuse-leap" and "opensuse-tumbleweed",
            // not "opensuse". Neither matched, so the plan was never selected and the
            // repository aliases kept their colons -- which is exactly the
            // "Can't open solv-file: .../openSUSE:repo-oss/solv" the cleanup exists to
            // fix. The session then had no repos at all.
            "suse", "sles", "sled", "opensuse" -> "opensuse"
            "almalinux", "alma" -> "almalinux"
            "rocky", "rockylinux" -> "rocky"
            "manjaro-arm" -> "manjaro"
            "void" -> "void"
            "kali-linux" -> "kali"
            // Adélie and Chimera are musl/apk like Alpine. Parrot, Pardus and Trisquel
            // are Debian derivatives, so the existing startsWith("debian") fallback
            // catches them by ID already; they are listed for the plan, not the mapping.
            "adelie", "chimera" -> "alpine"
            // ARMtix reports itself as artix in some images and armtix in others. Both
            // must reach the pacman plan: it was falling through to the empty plan, so
            // pacman ran with no sandbox fix and failed on every device.
            "armtix", "artix", "artixlinux" -> "armtix"
            "pardus" -> "pardus"
            "parrot" -> "parrot"
            "trisquel" -> "trisquel"
            "slackware" -> "slackware"
            // Oracle Linux reports ID="ol". It has no "oracle" ID at all, so the plan
            // keyed "oracle" was unreachable: dnf kept the image's own repositories,
            // whose baseurls are yum$ociregion.$ocidomain and resolve to nothing outside
            // Oracle Cloud, and the session then fell back to whatever it could reach.
            "ol", "oracle" -> "oracle"
            else -> when {
                key.startsWith("debian") -> "debian"
                key.startsWith("opensuse") -> "opensuse"
                else -> null
            }
        }
    }

    private val pacmanXfer =
        // Only rewrite it when it is not already set.
        //
        // This deleted the active line first and then substituted only the commented
        // ones. Images built since #14 carry a working XferCommand, so the delete removed
        // it and the substitution had nothing left to replace, leaving pacman on its own
        // downloader and printing
        //   >>> Warning: could not set the pacman download command.
        // on every launch.
        "if ! grep -q '^XferCommand' /etc/pacman.conf; then " +
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
            "fi; " +
            "grep -q '^XferCommand' /etc/pacman.conf || " +
            "echo '>>> Warning: could not set the pacman download command.'; " +
            ""

    // pacman refuses to sync anything on a kernel without Landlock:
    //
    //   error: restricting filesystem access failed because Landlock is not
    //          supported by the kernel!
    //   error: switching to sandbox user 'alpm' failed!
    //   error: failed to synchronize all databases
    //
    // Android has never implemented Landlock, so every pacman here hits it -- the
    // Arch and ARMtix images do, on every device. Manjaro did not, which is luck
    // rather than a difference: the flag is applied to every pacman distro here
    // so none of them depends on it.
    //

    private val pacmanSandbox =
        // Two separate failures, not one:
        //
        //   error: restricting filesystem access failed because Landlock is not
        //          supported by the kernel!
        //   error: switching to sandbox user 'alpm' failed!
        //
        // DisableSandbox covers the first: Android has never implemented Landlock.
        // It says nothing about the second, which is pacman dropping privileges to
        // the alpm user. That user does exist in the image (uid 970) -- the switch
        // fails because proot has no working setuid, not because it is missing.
        //
        // DownloadUser = root is the second half. The alpm user was only ever there
        // so that an unprivileged download could not write into the cache; running
        // as root already satisfies that, so nothing is loosened beyond pacman's
        // own confinement.
        //
        // Signature checking is untouched. This only affects where downloads land.
          // Only written when the installed pacman actually knows the directive.
          //
          // Manjaro prints, on every pacman call:
          //
          //   warning: config file /etc/pacman.conf, line 10: directive 'DownloadUser' in
          //   section 'options' not recognized.
          //   warning: config file /etc/pacman.conf, line 11: directive 'DisableSandbox' in
          //   section 'options' not recognized.
          //
          // pacman ignores what it does not recognise and carries on, so the settings
          // bought nothing while adding two warnings to every command. DownloadUser
          // arrived in pacman 6.0 and DisableSandbox in 6.1; Manjaro ships neither, and
          // an older pacman has no Landlock sandbox for the directive to disable.
          "_pv=$(pacman --version 2>/dev/null | head -1); " +
              "_pv=\${_pv#pacman }; _pv=\${_pv#v}; " +
              "_pm=\${_pv%%.*}; _pr=\${_pv#*.}; _ps=\${_pr%%.*}; " +
              "if [ -f /etc/pacman.conf ] && " +
              "{ [ \"\$_pm\" -gt 6 ] || { [ \"\$_pm\" -eq 6 ] && [ \"\$_ps\" -ge 1 ]; }; }; then " +
              "grep -q '^DisableSandbox' /etc/pacman.conf || " +
              "sed -i '/^\\[options\\]/a DisableSandbox' /etc/pacman.conf; " +
              "fi; " +
              "if [ -f /etc/pacman.conf ] && " +
              "{ [ \"\$_pm\" -gt 6 ] || [ \"\$_pm\" -eq 6 ]; }; then " +
              "sed -i -E 's/^[[:space:]]*#?[[:space:]]*DownloadUser[[:space:]]*=.*/" +
              "DownloadUser = root/' /etc/pacman.conf; " +
              "grep -q '^DownloadUser' /etc/pacman.conf || " +
              "sed -i '/^\\[options\\]/a DownloadUser = root' /etc/pacman.conf; " +
              "fi"

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

    // There was a `gccLibsRepair` step here, on both Arch and Manjaro, that installed
    // `libgcc` and `libstdc++` with --overwrite to clear a file conflict Arch's own
    // packaging had created: `gcc-libs` used to own `libgcc_s.so.1` and `libstdc++.so.6`,
    // and installing the split packages then aborted with "failed to commit transaction
    // (conflicting files)".
    //
    // It is gone because neither image can reach that condition, measured against the
    // published tarballs rather than assumed:
    //
    //   Arch     gcc-libs owns 0 files          -- a meta package
    //            libgcc   owns usr/lib/libgcc_s.so.1
    //            libstdc++ owns usr/lib/libstdc++.so.6
    //
    //   Manjaro  gcc-libs owns 54 files, including the runtimes
    //            there is no libgcc or libstdc++ package at all
    //
    // So on Arch the split has already happened and there is nothing to repair; on
    // Manjaro the split never happened, so there is nothing to repair either. The guard
    // was checking `pacman -Qq gcc-libs`, which is true on both, so the step ran on
    // every healthy image of both distributions and could only ever fail there -- the
    // image ships no sync databases, so `pacman -S` resolved no target:
    //
    //   Arch     >>> Warning: could not clear the gcc-libs conflict. pacman said:
    //               error: target not found: libgcc
    //   Manjaro  >>> Warning: could not clear the gcc-libs conflict.
    //
    // Rewriting the guard to ask the better question only quieted Arch and left Manjaro
    // reporting it, which is the wrong shape entirely: a step that exists for a condition
    // no published image has is not a step, it is noise on every first launch.
    //
    // The rule that made this worth keeping -- a distro packaging change can conflict
    // with an image we shipped -- is real, and the way to honour it is to notice the
    // conflict when it happens, not to keep paying for a repair that never fires.

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
    /**
     * Oracle's repositories, from the layout Oracle documents rather than the RHEL one
     * rocky and almalinux use.
     *
     * The segment names are capitalised differently and the path has no "os" part:
     *
     *   https://yum.oracle.com/repo/OracleLinux/OL10/baseos/latest/aarch64
     *   https://yum.oracle.com/repo/OracleLinux/OL10/appstream/aarch64
     *
     * Both verified to serve a repomd.xml. Adding an "/os" segment -- the shape every
     * other RHEL-family mirror uses -- returns 404, which is why the first attempt at
     * this looked like a dead host.
     *
     * The image's own repositories are disabled first. They are declared against
     * yum$ociregion.$ocidomain, which resolves only inside Oracle Cloud, and dnf
     * treats a repository it cannot fetch as a reason to try something else.
     */
    private fun rhelReposOracle(): String = buildString {
        append(disableReposThorough)
        append("printf '%s\\n'")
        append(" '[ol10_baseos_latest]' 'name=Oracle Linux ${'$'}releasever BaseOS (${'$'}basearch)' " +
            "'baseurl=https://yum.oracle.com/repo/OracleLinux/OL${'$'}releasever/baseos/latest/${'$'}basearch' " +
            "'gpgkey=file:///etc/pki/rpm-gpg/RPM-GPG-KEY-oracle' 'gpgcheck=1' 'enabled=1' ''")
        append(" '[ol10_appstream]' 'name=Oracle Linux ${'$'}releasever AppStream (${'$'}basearch)' " +
            "'baseurl=https://yum.oracle.com/repo/OracleLinux/OL${'$'}releasever/appstream/${'$'}basearch' " +
            "'gpgkey=file:///etc/pki/rpm-gpg/RPM-GPG-KEY-oracle' 'gpgcheck=1' 'enabled=1' ''")
        append(" > /etc/yum.repos.d/ol10-oracle.repo; ")
    }

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

    private val opensuseClean =
        // Two jobs, both narrow: leave the repository files alone, and keep the cache
        // writable. That is all.
        //
        // The repository files used to be rewritten -- the section header de-coloned
        // and the `service=` line deleted -- on every session start. That was the
        // single worst change in this file, and it is worth recording why, because the
        // reasoning sounded right and was exactly backwards.
        //
        // libzypp names a cache directory after the repository alias, and a repository
        // supplied by a service is aliased `service:alias`. So for openSUSE the cache
        // lives at:
        //
        //   /var/cache/zypp/solv/openSUSE:repo-oss/solv
        //
        // which is the exact path in every "Can't open solv-file" report this project
        // has ever produced, and it is where the image ships its index. The `service=`
        // line is what puts the colon there.
        //
        // Deleting that line and renaming the header renamed every repository to one
        // the image ships no index for. The shipped index became unreachable, zypper
        // deleted it as stale, and rebuilt it with repo2solv -- which it cannot report
        // the failure of, for reasons in [zyppSolvRepair]. Every session start, so no
        // reinstall could escape it.
        //
        // https is kept. Both Leap 16.0 aarch64 baseurls answer in well under a second,
        // so this is a preference and not a fix for anything; it corrects an image that
        // predates the change without touching anything else.
        //
        // Nothing in the cache is a secret. It is re-downloadable repository metadata.
        "mkdir -p /var/cache/zypp/solv /var/cache/zypp/raw /var/cache/zypp/packages; " +
            "for r in /etc/zypp/repos.d/*.repo /usr/share/zypp/repos.d/*.repo; do " +
            "[ -e \"\$r\" ] || continue; " +
            "sed -i -e 's|^baseurl=http://cdn\\.opensuse\\.org|baseurl=https://download.opensuse.org|' \"\$r\"; " +
            "sed -i -e 's|^gpgkey=http://cdn\\.opensuse\\.org|gpgkey=https://download.opensuse.org|' \"\$r\"; " +
            "a=\$(grep -m1 '^\\[' \"\$r\" | cut -c2- | rev | cut -c2- | rev); " +
            "[ -n \"\$a\" ] || continue; " +
            "mkdir -p \"/var/cache/zypp/solv/\$a\" \"/var/cache/zypp/raw/\$a\" 2>/dev/null; " +
            "done; " +
            // A session runs as the app's own uid while the image built the cache as
            // root, so a root-owned cache is a directory the session cannot write.
            "chmod -R 777 /var/cache/zypp 2>/dev/null; "

    /**
     * Rebuilds the package index when it is missing, in the open, on purpose.
     *
     * ## Why this cannot be left to zypper
     *
     * zypper deletes the index whenever repository metadata changes, then rebuilds it by
     * running `repo2solv`. It decides whether that worked purely from the child's exit
     * status -- and two of libzypp's own threads race to reap that child:
     *
     *   CleanerThread::run()          waitpid(pid, &status, WNOHANG), every 100 ms
     *   ForkSpawnEngine::isRunning()  waitpid(pid, &status, WNOHANG), the same child
     *
     * Whichever loses gets -1. The engine's case is the damaging one:
     *
     *   case -1: ERR << "waitpid(...) returned error"; return false;
     *
     * It returns *without* assigning `_exitStatus`, which `start()` initialised to 0.
     * `ExternalProgram::close()` hands that 0 back, `repo2Solv()` sees `ret == 0` and
     * records success. A repo2solv that failed hard and immediately is recorded as
     * having worked, and `setCacheStatus` then writes a cookie describing a cache that
     * was never produced. The next command fails with:
     *
     *   Can't open solv-file: /var/cache/zypp/solv/openSUSE:repo-oss/solv
     *
     * That is not a cache-building failure. It is libzypp opening the result back after
     * its builder already claimed success (Repository::addSolv, from loadFromCache).
     *
     * So the build happens here, where the exit code means something and the output is
     * checked rather than assumed. There is no configuration that avoids this.
     *
     * ## Why it is not in the background
     *
     * It was, and the session became unusable: its output interleaved with the prompt,
     * a job-control line landed mid-session, and it held the zypp system lock so the
     * user's own next command came back with "System management is locked by the
     * application with pid N (zypper)". A multi-minute operation that says nothing
     * looks exactly like a hang, and being unable to see or stop it is worse than
     * waiting for it.
     *
     * So: synchronous, and it explains itself before it starts.
     *
     * ## Why it is cheap
     *
     * The check is one `test -s` per repository, on every session start. In steady
     * state it finds every index present, prints nothing and does nothing. openSUSE
     * republishes `repo-oss` metadata several times a day, so the repair is not rare --
     * but it is minutes only on the session start where something was actually missing.
     */
    private val zyppSolvRepair =
        "_need=0; " +
            "for d in /var/cache/zypp/raw/*/repodata; do " +
            "  [ -d \"\$d\" ] || continue; " +
            // openSUSE names it <sha256>-primary.xml.<ext>. The obvious glob,
            // primary.xml.*, matches nothing at all, and a check that silently matches
            // nothing makes the whole block a no-op -- which is what it was, for a week,
            // while it downloaded metadata and built no index whatsoever.
            "  ls \"\$d\"/*-primary.xml.* >/dev/null 2>&1 || continue; " +
            "  n=\$(basename \"\$(dirname \"\$d\")\"); " +
            "  [ -s \"/var/cache/zypp/solv/\$n/solv\" ] || _need=1; " +
            "done; " +
            "if [ \"\$_need\" = 1 ]; then " +
            "  echo 'openSUSE: the package index is missing.'; " +
            "  echo 'openSUSE: zypper deleted it when repository metadata changed and its'; " +
            "  echo 'openSUSE: own rebuild cannot report failure, so it is built here.'; " +
            "  echo 'openSUSE: refreshing metadata, then building. A couple of minutes.'; " +
            "  zypper --non-interactive refresh; " +
            "  echo 'openSUSE: building the index.'; " +
            "  for d in /var/cache/zypp/raw/*/repodata; do " +
            "    [ -d \"\$d\" ] || continue; " +
            "    ls \"\$d\"/*-primary.xml.* >/dev/null 2>&1 || continue; " +
            "    n=\$(basename \"\$(dirname \"\$d\")\"); " +
            "    o=\"/var/cache/zypp/solv/\$n/solv\"; " +
            "    [ -s \"\$o\" ] && continue; " +
            "    mkdir -p \"/var/cache/zypp/solv/\$n\" 2>/dev/null; " +
            "    printf 'openSUSE:   %s ... ' \"\$n\"; " +
            "    if repo2solv -o \"\$o\" -X \"\$d\" >/dev/null 2>&1 && [ -s \"\$o\" ]; then " +
            "      echo 'done'; " +
            "    else " +
            "      echo 'FAILED'; " +
            "    fi; " +
            "  done; " +
            // Whether the index is usable is decided by looking for it, not by counting
            // what this script built. Counting reported failure whenever zypper's own
            // rebuild had succeeded and there was nothing left to do -- which is the
            // good case, announced as the bad one.
            //
            // zypper's own exit status is ignored above on purpose: it returns non-zero
            // because of the bug this exists to work around.
            "  _bad=0; " +
            "  for d in /var/cache/zypp/raw/*/repodata; do " +
            "    [ -d \"\$d\" ] || continue; " +
            "    ls \"\$d\"/*-primary.xml.* >/dev/null 2>&1 || continue; " +
            "    n=\$(basename \"\$(dirname \"\$d\")\"); " +
            "    [ -s \"/var/cache/zypp/solv/\$n/solv\" ] || { echo \"openSUSE:   \$n is still missing\"; _bad=1; }; " +
            "  done; " +
            "  if [ \"\$_bad\" = 0 ]; then " +
            "    echo 'openSUSE: package index ready.'; " +
            "  else " +
            "    echo 'openSUSE: no package index could be built -- packages cannot be'; " +
            "    echo 'openSUSE: installed until this succeeds. The reason is above.'; " +
            "  fi; " +
            "fi; "

    // A crashed or segfaulted apt/dpkg leaves its lock files behind and every later
    // apt call then fails with "Could not get lock /var/lib/dpkg/lock". The lock is
    // only removed when no apt or dpkg process is actually running, so a genuinely
    // busy system is left alone.
    private val staleLockRepair =
        "    for _l in /var/lib/apt/lists/lock /var/lib/dpkg/lock /var/lib/dpkg/lock-frontend " +
            "/var/cache/apt/archives/lock /var/lib/pacman/db.lck; do\n" +
            "        [ -e \"\$_l\" ] || continue\n" +
            "        _busy=0\n" +
            "        for _p in /proc/[0-9]*; do\n" +
            "            _c=\$(cat \"\$_p/comm\" 2>/dev/null) || continue\n" +
            "            case \"\$_c\" in apt|apt-get|dpkg|dpkg-deb|unattended*|pacman) _busy=1; break ;; esac\n" +
            "        done\n" +
            "        if [ \"\$_busy\" = 0 ]; then rm -f \"\$_l\"; fi\n" +
            "    done\n"

    private fun line(cmd: String) = if (cmd.isEmpty()) "" else "    $cmd\n"

    // install, quiet, prepare, repair, update
    private fun plan(distro: String): List<String> = when (distro) {
        "alpine" -> listOf("apk add", "-q", "", "", "")
        "debian", "ubuntu", "kali" -> listOf(
            "DEBIAN_FRONTEND=noninteractive apt-get install -y", "-qq", machineId, "",
            ""
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
        // Oracle Linux resolved a "Fedora $releasever" repository instead of its own,
        // the same fault rocky and almalinux had, and for the same reason: the repo is
        // declared somewhere the narrow disable does not reach. It needs the thorough
        // disable and its own repository definitions.
        "oracle" -> listOf(
            "dnf install -y", "-q",
            rhelReposOracle(), "", ""
        )
        "void" -> listOf("xbps-install -S", "", xbpsDirs, "", "")
        // Adelie and Chimera are apk like Alpine but their images already ship a working
        // bash, so they fall through to the empty plan below rather than running apk at all.
        "pardus", "parrot", "trisquel" -> listOf(
            "DEBIAN_FRONTEND=noninteractive apt-get install -y", "-qq", machineId, "",
            ""
        )
        // ARMtix is pacman like Arch, and needs the same sandbox fix: Android has no
        // Landlock, so pacman refuses to sync. The mirrors are left entirely alone --
        // its own mirrorlist is self-consistent.
        "armtix" -> listOf(
            "pacman -S --noconfirm --needed", "", pacmanSandbox, "",
            ""
        )
        // Slackware has no package manager this app drives, and applying the pacman
        // setup to it produced "sed: /etc/pacman.conf: No such file or directory"
        // followed by a warning, on a distribution that has never heard of pacman.
        "slackware" -> listOf(":", "", "", "", "")
        // Arch does not upgrade at first start. `pacman -Syu` replaced glibc and bash
        // inside the very rootfs the script runs from, killing it at its final
        // `exec bash -i` with "/root/.startup[31]: /bin/bash: No such file or
        // directory", and it also meant a distribution's package set depended on
        // the day the user first launched it. The image is updated explicitly, by
        // the user, from Package Updates. What remains is the conflict repair and
        // the sandbox settings, both of which are local and idempotent.
        "arch" -> listOf(
            "pacman -S --noconfirm --needed", "", pacmanSandbox + "; " + archMirrors, ""
        )
        "manjaro" -> listOf(
            "pacman -S --noconfirm --needed", "", pacmanSandbox + "; " + manjaroMirrors, "",
            ""
        )
        // The package cache is built into the image, not into the session. It was
        // built here instead, and every version of that was wrong: synchronous it
        // blocked the prompt for minutes behind a "setup" that looked hung, and
        // backgrounded it interleaved its output with the prompt and held the zypp
        // system lock so the user's own next command was refused. Building it at
        // image time is root in a chroot with no proot and nothing to wait for.
        // The cache repair sits in the idempotent slot with opensuseClean, not in the
        // one-shot setup block: it must run again whenever upstream republishes
        // metadata, and it costs nothing when it finds nothing to do.
        "opensuse" -> listOf(
            "zypper --non-interactive install -y", "", opensuseClean + zyppSolvRepair, ""
        )
        // Gentoo's stage3 image already ships a resolved @world and a working bash, so
        // there is nothing to install on first start. `emerge --sync` is deliberately
        // absent: it makes the image's package set depend on the day the user first
        // launches it, and portage will happily do it themselves.
        "gentoo" -> listOf(":", "", "", "", "")
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

    /**
     * Identifies the plan a given install has already run.
     *
     * The first-start marker used to be a bare file whose only property was existing.
     * It is written on the first start and from then on the whole plan is skipped --
     * so every fix shipped in a later APK was dead on any install that had already
     * started even once. That is how openSUSE kept reporting colon repository aliases
     * after the fix for them shipped: the fix was never executed.
     *
     * Deriving the marker from the plan means a changed plan runs again, while an
     * unchanged one still does not repeat work the user has already paid for.
     */
    internal fun planToken(steps: List<String>): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(steps.joinToString("\u0000").toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    fun buildStartupScript(distro: String): String {
        val p = plan(distro)
        val token = planToken(p)
        return buildString {
            append(MARKER).append('\n')
            // Deliberately outside the one-shot block. Repository configuration is
            // cheap and idempotent, so it is re-applied on every start. Keeping it
            // inside meant a first run that failed to reach its mirrors wrote a
            // broken mirrorlist, still touched /root/.init_done because bash was
            // present, and so never repaired itself: pacman stayed broken forever
            // while the terminal cheerfully printed "Setup complete".
            // The shim defines sudo for the steps below, so it has to exist before any of
            // them run -- not after. openSUSE's plan rewrites /etc/zypp/repos.d, and with the
            // shim defined afterwards it ran as the app's unprivileged uid, failed on
            // every file, and the setup reported success anyway.
            append(SUDO_SHIM)

            append(line(p[2]))
            append("if [ \"\$(cat /root/.init_done 2>/dev/null)\" != \"$token\" ]; then\n")
            append("    echo '>>> First-time distro setup...'\n")
            // No package operation runs here any more. The image is built with its
            // package database synced, so a first-start `apt-get upgrade` repeated work
            // the build already did, on the user's battery and their data.
            //
            // What still decides whether setup counts as done is the setup step itself.
            // It used to be ignored: a failed repair still wrote /root/.init_done and
            // still printed "Setup complete.", so the rootfs stayed in the state the
            // repair was meant to fix and nothing ever retried.
            append(line(p[3]))
            append("    _setup_rc=$?\n")
            append(bashInstallFor(p[0], p[1]))
            append("    if [ \"\$_setup_rc\" != 0 ]; then\n")
            append("        echo '>>> setup did not complete; retrying on next start.'\n")
            append("    elif command -v bash >/dev/null 2>&1; then\n")
            append("        printf '%s' '$token' > /root/.init_done\n")
            append("        echo '>>> Setup complete.'\n")
            append("    else\n")
            append("        echo '>>> bash is still unavailable; retrying on next terminal start.'\n")
            // No `else` branch: it used to announce ">>> Setup already complete; skipping
            // the system update." on every single launch, which is the one piece of output
            // that told the user nothing they could not already see from a prompt. The
            // check itself is untouched — this is `if [ ! -f /root/.init_done ]; then ... fi`
            // either way, so setup still runs once and is still verified every launch.
            append("    fi\n")
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
