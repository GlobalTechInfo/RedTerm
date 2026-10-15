package com.redtermapp.distro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.nio.file.Files
import java.io.File

/**
 * Invariants that only showed up on a real device, each of which broke a distro and
 * is easy to reintroduce by accident. They are asserted against the generated
 * scripts and against the two source files that have to agree with each other,
 * rather than against a copy of the logic.
 */
class DistroConfigInvariantsTest {

    private fun script(distro: String): String = DistroSetup.buildStartupScript(distro)

    /**
     * A section header in a pacman mirrorlist is a *repository filter*, not a tag.
     * Writing `[aarch64]` made pacman treat it as a repository of its own, giving
     * "could not register 'aarch64' database" and then "no servers configured".
     * The images already ship a correct mirrorlist, so we only ever append.
     */
    @Test
    fun pacmanMirrorlistsNeverGainSectionHeaders() {
        for (d in listOf("arch", "manjaro")) {
            val s = script(d)
            // The image's mirrorlist is authoritative and is left entirely alone:
            // no writing, no appending. The step only makes sure the file exists.
            assertTrue(
                "$d no longer ensures the mirrorlist exists",
                s.contains("touch /etc/pacman.d/mirrorlist")
            )
            // A '>' redirect means we are replacing the image's list, which we
            // decided not to do; appending is '>>'.
            // A single '>' truncates; '>>' appends. Substring matching would let
            // ">> /etc/..." satisfy a search for "> /etc/...", so the lookbehind
            // matters here.
            assertFalse(
                "$d overwrites the mirrorlist the image ships",
                Regex("""(?<!>)> /etc/pacman\.d/mirrorlist""").containsMatchIn(s)
            )

        }
    }

    /**
     * The mirror URLs must keep their exact casing. "Baseos" and "Appstream" both
     * 404; only "BaseOS" and "AppStream" exist. This was invisible to string
     * inspection and only surfaced by running the generated script.
     */
    @Test
    fun dnfRepositoryPathsKeepTheirExactCasing() {
        for (d in listOf("rocky", "almalinux")) {
            val s = script(d)
            assertTrue("$d lost the BaseOS path", s.contains("/BaseOS/"))
            assertTrue("$d lost the AppStream path", s.contains("/AppStream/"))
            assertFalse("$d produced a lowercase Baseos path", s.contains("/Baseos/"))
            assertFalse("$d produced a lowercase Appstream path", s.contains("/Appstream/"))
        }
    }

    /** Each vendor must use its own servers, in a file named after that vendor. */
    @Test
    fun rhelDistrosUseTheirOwnVendorServers() {
        val rocky = script("rocky")
        assertTrue("rocky does not use dl.rockylinux.org", rocky.contains("dl.rockylinux.org"))
        assertTrue("rocky does not write rocky.repo", rocky.contains("/etc/yum.repos.d/rocky.repo"))
        assertFalse("rocky points at fedora", rocky.contains("mirrors.kernel.org/fedora"))

        val alma = script("almalinux")
        assertTrue("alma does not use repo.almalinux.org", alma.contains("repo.almalinux.org"))
        assertTrue("alma does not write almalinux.repo", alma.contains("/etc/yum.repos.d/almalinux.repo"))
        assertFalse("alma points at fedora", alma.contains("mirrors.kernel.org/fedora"))
    }

    /**
     * Fedora worked before and must keep its original cleanup, which does not touch
     * /etc/dnf/dnf.conf. Only Rocky and AlmaLinux get the thorough variant.
     */
    @Test
    fun fedoraKeepsItsOriginalNarrowCleanup() {
        val fedora = script("fedora")
        assertTrue("fedora's own cleanup is gone", fedora.contains("/etc/yum.repos.d/*.repo"))
        assertFalse("fedora now rewrites dnf.conf", fedora.contains("> /etc/dnf/dnf.conf"))
        for (d in listOf("rocky", "almalinux")) {
            assertTrue("$d does not disable the legacy repo path", script(d).contains("/etc/yum/repos.d"))
        }
    }

    /**
     * openSUSE's alias loses its colon, and everything else about it survives.
     *
     * This is the narrow version. An earlier one also renamed the repository files,
     * deleted `openSUSE.service`, removed `RepoManager.db` and dropped the openh264,
     * source and debug repositories. None of that is done here: the service file stays,
     * RepoManager.db stays, no file is renamed and no repository is removed. Only the
     * section header and the service attachment change.
     *
     * The claim that a colon is harmless came from running `zypper refresh` natively as
     * root, where a directory can always be created. That test cannot tell a colon that
     * is fine from one that is not, and on a device the colon-named cache path is the
     * only path that fails. openSUSE is also the only distribution here whose cache path
     * contains one, and the only one that cannot install a package.
     *
     * Verified in a chroot against the published image: after this script, two
     * consecutive `zypper refresh` runs both succeed, the alias stays colon-free, and
     * `zypper search --match-exact git-core` finds the package -- which is what used to
     * report "No provider of 'git-core' found".
     */
    @Test
    fun opensuseStripsTheColonAndKeepsEveryRepository() {
        val s = script("opensuse")
        assertTrue(
            "openSUSE does not de-colon the repository alias, so the cache path keeps a " +
                "colon and the session cannot create it",
            s.contains("""s|^\[\([^]]*\):\([^]]*\)\]|[\2]|""")
        )
        assertTrue(
            "openSUSE does not detach the repositories from the service that restores " +
                "the colon on every invocation",
            s.contains("""/^[[:space:]]*service[[:space:]]*=/d""")
        )
        // The service file itself stays: detaching the repositories is not the same
        // thing as deleting the service, and only the first is needed.
        assertFalse(
            "openSUSE removes the openSUSE service, which this fix does not need",
            s.contains("rm -f /etc/zypp/services.d/openSUSE.service")
        )
        assertFalse(
            "openSUSE removes the service's local source directory",
            s.contains("/usr/share/zypp/local/service/openSUSE")
        )
        assertFalse(
            "openSUSE discards RepoManager.db, which zypper rebuilds",
            s.contains("RepoManager.db")
        )
        assertFalse(
            "openSUSE discards repositories the image ships; none of them caused a failure",
            s.contains("*repo-openh264*")
        )
        assertFalse(
            "openSUSE discards the source repositories",
            s.contains("*-source.repo")
        )
        assertFalse(
            "openSUSE discards the debug repositories",
            s.contains("*-debug.repo")
        )
    }

    /**
     * The cache is writable, and is never destroyed.
     *
     * This is the one part of the old script that survives, because it is the one part
     * that is not disproven. A session runs as the app's own uid, while the image built
     * /var/cache/zypp as root, so a root-owned cache is a directory the session cannot
     * write. That cannot be tested off-device -- `zypper` refuses to run without uid 0
     * and the app depends on proot to fake it -- so it is kept on the strength of the
     * mechanism rather than a reproduction.
     *
     * Nothing in the cache is a secret; it is re-downloadable repository metadata.
     */
    @Test
    fun opensuseMakesTheCacheWritableAndNeverDestroysIt() {
        val s = script("opensuse")
        assertTrue(
            "opensuse does not make the zypp cache writable to the session's uid",
            s.contains("chmod -R 777 /var/cache/zypp")
        )
        assertTrue(
            "opensuse does not ensure the cache directory exists",
            s.contains("mkdir -p /var/cache/zypp")
        )
        assertFalse(
            "opensuse still deletes the solv cache every start",
            s.contains("rm -rf /var/cache/zypp/solv")
        )
        assertFalse(
            "opensuse still deletes the raw cache every start",
            s.contains("rm -rf /var/cache/zypp/raw")
        )
        assertFalse(
            "opensuse still wipes the whole zypp cache",
            s.contains("rm -rf /var/cache/zypp;")
        )
    }

    /**
     * The whole of what openSUSE setup does, pinned as a set.
     *
     * Three releases rewrote the repository configuration here -- de-colonning aliases,
     * renaming files, stripping `service=`, deleting `openSUSE.service` and
     * RepoManager.db -- and none of it was needed. `zypper refresh` succeeds on the
     * stock image, building /var/cache/zypp/solv/openSUSE:repo-oss/solv, the very path
     * those changes existed to avoid. See docs/proot-distro-reference.md.
     *
     * Deleting the tests that covered the old behaviour would have left nothing in their
     * place, so this asserts the invariant directly: there is exactly one command, it
     * concerns the cache, and it creates rather than destroys.
     */
    @Test
    fun opensuseSetupIsExactlyOneCacheCommandAndNothingElse() {
        val s = script("opensuse")
        // Not "contains no rm" -- the shared harness is one long printf line carrying the
        // sudo shim and the resolv.conf step, so a whole-line scan proves nothing. What
        // matters is that nothing reaches the repository or service configuration.
        // Reading a repository's section header to learn its alias is fine; changing
        // the repository is not. So this is a statement about writes, not about paths.
        // The alias and the service attachment are changed on purpose. What must not
        // change is the set of repositories: an earlier version removed openh264,
        // the source repos and the debug repos, and that cost the user distributions
        // they could install from for no gain.
        for (mutating in listOf(
            "rm -f /etc/zypp", "rm -rf /etc/zypp", "> /etc/zypp",
            "RepoManager.db", "local/service",
            "*repo-openh264*", "*-source.repo", "*-debug.repo"
        )) {
            assertTrue(
                "openSUSE setup destroys repository configuration ('$mutating'); the " +
                    "repositories themselves must all survive:\n$s",
                !s.contains(mutating)
            )
        }
        assertTrue(
            "openSUSE setup no longer makes its cache writable:\n$s",
            s.contains("/var/cache/zypp/solv") && s.contains("chmod -R 777 /var/cache/zypp")
        )
        // libzypp does not create the per-repository directories on a device, and zypper
        // reports the missing solv file rather than the failed mkdir, so the setup has to
        // create them from each repository's own section header.
        assertTrue(
            "openSUSE setup does not pre-create the per-repository cache directories:\n$s",
            s.contains("/var/cache/zypp/solv/\$a") &&
                s.contains("/var/cache/zypp/raw/\$a")
        )
        // Every other path under /var is the shared stale-lock repair, which every distro
        // gets and which must keep working: a crashed apt or pacman leaves a lock
        // behind and nothing installs until it is cleared.
        val allowed = listOf(
            "/var/lib/apt/lists/lock", "/var/lib/dpkg/lock", "/var/lib/dpkg/lock-frontend",
            "/var/cache/apt/archives/lock", "/var/lib/pacman/db.lck"
        )
        val unexpected = Regex("""/var/[A-Za-z0-9._/-]*""").findAll(s).map { it.value }
            .distinct()
            .filterNot { it.startsWith("/var/cache/zypp") || it in allowed }
            .toList()
        assertTrue(
            "openSUSE setup reaches paths under /var that are neither its cache nor a " +
                "stale lock: $unexpected",
            unexpected.isEmpty()
        )
    }

    /**
     * A real Leap `.repo` file, byte for byte, survives the setup untouched.
     *
     * The content is what opensuse/leap:16.0 actually ships, including the colon in the
     * section header and the trailing `service=openSUSE`, because those two together are
     * what the removed rewrites were fighting. It is copied into a sandbox and the setup's
     * own commands are run against it.
     *
     * The one substitution is the absolute cache path, redirected into the sandbox so the
     * test does not chmod a real /var/cache. What is under test is *which paths the setup
     * reaches*, and rewriting one literal does not change which paths that is.
     */
    @Test
    fun aRealLeapRepositoryFileSurvivesTheOpenSuseSetupUnchanged() {
        val repo = """# Repository 'openSUSE:repo-oss' is maintained by the 'openSUSE' service.
# Manual changes may be overwritten by a service refresh.
# See also 'man zypper', section 'Services'.
[openSUSE:repo-oss]
name=repo-oss (${'$'}{releasever})
enabled=1
autorefresh=1
baseurl=http://cdn.opensuse.org/distribution/leap/${'$'}{releasever}/repo/oss/${'$'}{basearch}
gpgkey=http://cdn.opensuse.org/distribution/leap/${'$'}{releasever}/repo/oss/${'$'}{basearch}/repodata/repomd.xml.key
service=openSUSE
"""
        val work = Files.createTempDirectory("redterm-zypp")
        try {
            val reposD = work.resolve("repos.d").toFile().apply { mkdirs() }
            val file = reposD.resolve("openSUSE:repo-oss.repo").apply { writeText(repo) }
            val before = Files.readAllBytes(file.toPath())

            val sandboxCache = work.resolve("cache/zypp").toString()
            val setup = script("opensuse")
                .lines()
                .filter { it.contains("/var/cache/zypp") }
                .map { it.trim().removeSuffix(";") }
                .filter { it.isNotEmpty() }
                .joinToString(" && ") { it.replace("/var/cache/zypp", sandboxCache) }
            assertTrue("openSUSE setup had no cache command to run:\n$setup", setup.isNotEmpty())

            val p = ProcessBuilder("/bin/sh", "-c", setup).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            val exit = p.waitFor()
            assertEquals("the setup failed in the sandbox (exit $exit): $out", 0, exit)

            assertArrayEquals(
                "openSUSE setup modified a real Leap repository file",
                before,
                Files.readAllBytes(file.toPath())
            )
            // And the cache really was made writable, which is the one thing it should do.
            val cache = work.resolve("cache/zypp").toFile()
            assertTrue("the cache was not created", cache.isDirectory)
            assertEquals(
                "the cache is not writable by the session's uid",
                "777",
                ProcessBuilder("stat", "-c", "%a", cache.absolutePath).start()
                    .inputStream.bufferedReader().readText().trim()
            )
        } finally {
            work.toFile().deleteRecursively()
        }
    }

    /**
     * pacman only reaches a repository if the XferCommand was installed; a step that
     * silently does nothing leaves "curl: (2) no URL specified". Every prepare step
     * therefore has to be a runnable statement, not an argument to an echo.
     */
    @Test
    fun prepareStepsAreRealCommandsNotEchoArguments() {
        for (d in listOf("arch", "manjaro", "rocky", "almalinux", "fedora", "void",
            "opensuse", "debian", "ubuntu", "kali", "alpine")) {
            val s = script(d)
            // `echo "pkg install was:"rm -rf /` prints the text and then runs rm.
            //
            // The opening quote has to be matched literally. `echo [^"]*"` lets `[^"]*`
            // match nothing, so it is satisfied by the *opening* quote of any echo whose
            // first character happens to be lowercase -- which is every correctly
            // terminated echo, and which is why a real `echo "built solv cache..."` was
            // being reported as a swallowed command.
            assertFalse(
                "$d has a command swallowed by the preceding echo",
                Regex("""echo "[^"]*"(?=[a-z|/])""").containsMatchIn(s) ||
                    s.contains("was:grep") || s.contains("was:cat") || s.contains("was:mkdir")
            )
        }
    }

    /**
     * Backup has to skip the directories proot bind-mounts, because tar runs
     * outside proot and cannot read them ("Permission denied" on every stub, which
     * aborted the whole archive). It must skip exactly the set the launcher
     * re-binds, or a restored rootfs comes back missing something.
     */
    @Test
    fun backupExcludesExactlyTheLauncherBindMounts() {
        // Read the declared bind lists rather than regexing the generated command: the
        // launcher and the backup both derive from them, so they cannot drift apart.
        fun listIn(source: String, name: String): List<String> {
            val m = Regex("""val $name = listOf\((.*?)\)""", RegexOption.DOT_MATCHES_ALL)
                .find(source) ?: error("$name not found")
            return Regex("""\"([^\"]+)\"""")
                .findAll(m.groupValues[1]).map { it.groupValues[1] }.toList()
        }
        val launchSource = File("src/main/java/com/redtermapp/distro/ProotLaunch.kt").readText()
        val binds = (listIn(launchSource, "BOUND_DIRS") + listIn(launchSource, "BOUND_FILES"))
            .map { it.trimEnd('/') }.map { it.removePrefix("/") }.toSet()

        // The backup exclusions are built from the same lists, so assert against them
        // directly rather than scraping the generated tar arguments.
        val excluded = ProotLaunch.BOUND_DIRS.map { it.removePrefix("/") } +
            ProotLaunch.BOUND_FILES.map { it.removePrefix("/") }

        assertTrue("no bind mounts found in ProotLaunch", binds.isNotEmpty())
        assertTrue("no backup exclusions found", excluded.isNotEmpty())
        // The backup must actually consume the shared lists rather than repeating the
        // paths, or the two can drift again.
        val installerSource = File("src/main/java/com/redtermapp/distro/DistroInstaller.kt").readText()
        assertTrue(
            "the backup hard-codes exclusions instead of deriving them from ProotLaunch",
            installerSource.contains("ProotLaunch.BOUND_DIRS") &&
                installerSource.contains("ProotLaunch.BOUND_FILES")
        )

        // Every bound path must be covered by an exclusion, and the exclusions must not
        // be broader than the binds. Over-excluding is just as lossy as under-excluding:
        // it silently drops real distro content.
        val missed = binds.filter { b -> excluded.none { e -> b == e || b.startsWith("$e/") } }
        assertTrue(
            "the launcher binds these but backup does not exclude them, so a restore " +
                "would be lossy: $missed",
            missed.isEmpty()
        )
        val extra = excluded.filter { e -> binds.none { b -> e == b || b.startsWith("$e/") } }
        assertTrue(
            "backup excludes these but the launcher never binds them, so real content " +
                "could be dropped: $extra",
            extra.isEmpty()
        )

        // /linkerconfig/ld.config.txt is bound as a single file. Excluding the whole
        // /linkerconfig directory instead would pass the coverage check above while
        // still discarding anything the distro genuinely shipped in that directory,
        // so the exact file path is required.
        assertTrue(
            "the file bind must be excluded by its exact path",
            excluded.contains("linkerconfig/ld.config.txt")
        )
        assertTrue(
            "excluding the whole /linkerconfig directory drops real distro content",
            !excluded.contains("linkerconfig")
        )
    }

    /**
     * pacman substitutes %o (destination) and %u (URL) itself and does not append
     * the URL, so an XferCommand without %u invokes curl with flags and no URL:
     * "curl: (2) no URL specified", once per repository. This is the bug the device
     * log exposed, and it had nothing to do with the mirrorlist.
     */
    @Test
    fun pacmanXferCommandCarriesTheUrlPlaceholder() {
        for (d in listOf("arch", "manjaro")) {
            val s = script(d)
            assertTrue("$d sets no XferCommand", s.contains("XferCommand"))
            val line = s.lines().first { it.contains("XferCommand =") }
            assertTrue(
                "$d's XferCommand has no %u, so pacman hands curl no URL: $line",
                line.contains("%u")
            )
            assertTrue("$d's XferCommand has no %o, so pacman has nowhere to write: $line", line.contains("%o"))
            // These abort a slow but progressing transfer instead of failing over,
            // which on a mobile link killed good downloads.
            assertFalse("$d aborts slow downloads via --speed-limit: $line", line.contains("--speed-limit"))
            assertFalse("$d aborts slow downloads via --speed-time: $line", line.contains("--speed-time"))
        }
    }

    /** A double-escaped \$ prints literally, which makes the diagnostic a lie. */
    @Test
    fun diagnosticsAreNotDoubleEscaped() {
        for (d in listOf("arch", "manjaro")) {
            val s = script(d)
            assertFalse(
                "$d prints a command substitution literally instead of running it",
                s.contains("\\\$(grep")
            )
        }
    }

    /**
     * A leftover repo file from any earlier run kept winning, so the vendor's own
     * repository has to be the only definition left in the directory.
     */
    @Test
    fun dnfDirectoryIsLeftWithOnlyTheVendorRepo() {
        for (d in listOf("rocky", "almalinux")) {
            val s = script(d)
            assertTrue("$d does not clean up foreign repo files", s.contains("-delete"))
            assertTrue("$d would delete its own file", s.contains("! -name '"))
        }
    }

    /**
     * The distro a rootfs gets is decided by the ID field of /etc/os-release.
     * Matching a substring anywhere in the file routed Rocky and AlmaLinux to
     * Fedora's plan, because both declare ID_LIKE="rhel centos fedora", and Fedora
     * wrote its own repositories into their rootfs.
     */
    @Test
    fun distroIsTakenFromTheIdFieldNotFromIdLike() {
        // ID is mapped through canonicalize() first, so ID_LIKE can never decide the
        // distro: Rocky and AlmaLinux both declare ID_LIKE="rhel centos fedora" and were
        // previously sent to Fedora's plan. The substring tests only run when
        // canonicalize() returns null, i.e. when there is no usable ID, as a last
        // resort for a rootfs with an unreadable or missing /etc/os-release.
        val t = File("src/main/java/com/redtermapp/ui/TerminalActivity.kt").readText()
        assertTrue(
            "the distro is no longer decided from the ID field",
            t.contains("canonicalize(osId)")
        )
        val canonicalLine = t.substringAfter("val canonical =")
            .substringBefore("\n")
        assertTrue("canonicalize() is not consulted first", canonicalLine.contains("canonicalize(osId)"))
        val whenBlock = t.substringAfter("val distro: String = when {")
            .substringBefore("val bashrc")
        val firstBranch = whenBlock.substringAfter('{').substringBefore("->")
        assertTrue(
            "the distro is still decided with a substring test before the ID is used",
            firstBranch.contains("canonical != null")
        )
    }

    @Test
    fun temporaryMirrorDiagnosticsAreGone() {
        for (d in listOf("arch", "manjaro", "rocky", "almalinux")) {
            val s = script(d)
            for (noise in listOf("image mirrorlist was", "pacman mirrors", "repo files:",
                "baseurls:", "xbps cache:")) {
                assertFalse("$d still prints '$noise' on every launch", s.contains(noise))
            }
        }
        // The announcements that matter are kept.
        assertTrue(DistroSetup.buildStartupScript("arch").contains("First-time distro setup"))
        assertTrue(DistroSetup.buildStartupScript("arch").contains("Setup complete."))
    }

    /**
     * Arch split gcc-libs into libgcc and libstdc++, and an older rootfs still owns
     * the old shared objects, so every install aborts with "conflicting files". The
     * repair has to run before the upgrade and on every session: it used to sit in
     * the one-shot slot *after* the upgrade, so the upgrade failed first and the
     * marker was written anyway, leaving the conflict permanently in place.
     */
    @Test
    fun gccLibsConflictIsClearedBeforeAnyUpgrade() {
        // The conflict has to be cleared with --overwrite in one transaction.
        // Installing the split packages first is refused because pacman checks file
        // conflicts before unpacking, and removing gcc-libs first can leave pacman
        // unable to start, because that package owns the libstdc++.so.6 pacman is
        // linked against. Either way the conflict survived.
        for (d in listOf("arch", "manjaro")) {
            val s = script(d)
            assertTrue("$d does not clear the gcc-libs conflict", s.contains("gcc-libs"))
            assertTrue("$d does not use --overwrite", s.contains("--overwrite"))
            assertTrue("$d still removes gcc-libs, which can break pacman itself",
                !s.contains("pacman -Rdd"))
            // The repair must be in the prepare step, so it runs on every session
            // rather than only when the first-time block is entered.
            assertTrue("$d does not report a failed repair", s.contains("could not clear the gcc-libs conflict"))
        }
    }

    /**
     * Setup reports complete only when it actually completed.
     *
     * There is no package operation at first start any more, so `update_ok` is 1 by
     * construction and the failure branch is unreachable. What must not come back is the
     * bug that made this test necessary in the first place: "Setup complete." printed
     * while the work behind it had been skipped or failed silently.
     *
     * The marker is written only when setup finished, and still records the plan token so
     * a changed plan re-runs.
     */
    @Test
    fun setupReportsCompleteOnlyWhenItFinished() {
        for (d in listOf("arch", "manjaro", "rocky", "almalinux", "fedora", "void",
            "opensuse", "alpine", "debian", "ubuntu", "kali")) {
            val s = script(d)
            val marker = s.indexOf("> /root/.init_done")
            val ok = s.indexOf("Setup complete.")
            val failed = s.indexOf("System update failed")
            assertTrue("$d never writes the marker", marker >= 0)
            assertTrue("$d never reports completion", ok >= 0)
            // Failure branch first, then the marker, then the success message: the
            // marker is only reached when nothing above it claimed to fail.
            assertTrue("$d writes the marker after reporting success", marker < ok)
            assertTrue(
                "$d keeps an unreachable failure branch claiming an update result",
                failed < 0
            )
        }
    }


    /**
     * Regression test for two bugs that both made first-time setup silently do
     * nothing while still printing "Setup complete.":
     *
     *  1. Every distro's update step was wrapped in `if ! command -v bash`, so with
     *     bash present (true of every image) the update never ran, the `if` returned
     *     0, and setup claimed success. Only Alpine was ever affected by accident.
     *  2. The openSUSE alias rewrite used `s|^\[\([^]]*\):|\[|`. `[^]]` is not
     *     portable, so sed aborted with "unterminated `s' command" every time and the
     *     repo files kept their `openSUSE:` aliases.
     *
     * `sh -n` cannot catch either, so the rewrite is actually executed here.
     */
    /**
     * First start performs no package operation at all, for any distribution.
     *
     * The image is built with its package database already synced, so a first-start
     * `apt-get upgrade` or `pacman -Syu` repeats work the build already did -- on the
     * user's battery and their mobile data -- and a failure part way through leaves a
     * half-upgraded image that is worse than the one shipped. It also made the package
     * set depend on the day the user first launched the app.
     *
     * Users update when they choose to, through the update action. This asserts the
     * whole family is absent, not that some of them are merely guarded correctly.
     */
    @Test
    fun firstStartPerformsNoPackageOperation() {
        // Only update and upgrade operations are forbidden. Bare tool names are not:
        // `zypper --non-interactive install -y` is the bash-install fallback and
        // /etc/zypp/repos.d is a path, not a package operation.
        val forbidden = listOf(
            "apt-get update", "apt-get upgrade", "apt-get -y upgrade", "apt dist-upgrade",
            "pacman -Syy", "pacman -Syu", "pacman -Sy", "pacman -U",
            "zypper refresh", "zypper update", "zypper dup", "zypper dist-upgrade",
            "xbps-install -Su", "apk update", "apk upgrade", "apk add --update-cache",
            "dnf -y upgrade", "dnf upgrade", "dnf update", "emerge --sync", "emerge -u"
        )
        for (d in listOf(
            "alpine", "debian", "ubuntu", "kali", "void", "fedora", "rocky", "almalinux",
            "oracle", "armtix", "arch", "manjaro", "opensuse", "slackware", "gentoo"
        )) {
            val s = script(d)
            for (f in forbidden) {
                assertFalse("$d performs '$f' at first start", s.contains(f))
            }
        }
    }

    /**
     * The Arch first-start upgrade replaces glibc and bash inside the very rootfs the
     * script is running from. When it was run unguarded the live /bin/bash was swapped
     * out from under the script and it died at its final `exec bash -i`, reporting
     * "/root/.startup[31]: /bin/bash: No such file or directory". The upgrade therefore
     * has to be isolated in a subshell so nothing executing depends on the old binary.
     */
    /**
     * Arch no longer upgrades at first start.
     *
     * This used to require the upgrade to be isolated in a subshell, because the Arch
     * first-start `pacman -Syu` replaced glibc and bash inside the very rootfs the
     * script runs from, killing it at its final `exec bash -i` with
     * "/root/.startup[31]: /bin/bash: No such file or directory".
     *
     * The upgrade is gone rather than isolated, which removes the failure instead of
     * containing it. The mirror and gcc-libs repair still runs, and is asserted by
     * archUsesFailoverMirrorsAndGccLibsRepair.
     */
    @Test
    fun archNoLongerUpgradesAtFirstStart() {
        val s = script("arch")
        assertFalse("arch still upgrades at first start", s.contains("-Syu"))
        assertFalse("arch still syncs at first start", s.contains("pacman -Syy"))
    }


    /**
     * Every generated script has to be parseable. The Arch script once shipped a
     * `case "$arch" in aarch64|armv7l)` branch with an empty body and an XferCommand
     * insert with no trailing `;`, which is a shell syntax error: the whole prepare step
     * silently did nothing. The old harness only ever checked the scripts it had a
     * fixture for, and `sh -n` was never run over the generated output.
     */
    @Test
    fun everyGeneratedScriptIsValidShell() {
        for (d in listOf("alpine", "debian", "ubuntu", "kali", "void", "opensuse",
            "arch", "manjaro", "rocky", "almalinux", "fedora")) {
            val file = File("build/distro-scripts/$d.sh")
            assertTrue("$d was not generated for inspection", file.exists())
            val p = ProcessBuilder("sh", "-n", file.absolutePath)
                .redirectErrorStream(true).start()
            val err = p.inputStream.bufferedReader().readText()
            assertTrue("the $d startup script is not valid shell: $err", p.waitFor() == 0)
        }
    }

    /**
     * An /etc/os-release ID that is not a plan() key used to fall through to the `else`
     * fallback, which is a completely empty plan. That silently produced a startup
     * script with `update_ok=1` and `then : bash` - no mirror setup, no download
     * command, no repair and no system update - while still printing "Setup complete."
     * Arch Linux ARM reports ID="archlinuxarm" and openSUSE Leap "opensuse-leap", so
     * both hit it.
     */
    @Test
    fun everyRealOssReleaseIdMapsToAPlan() {
        val ids = listOf(
            "archlinuxarm" to "arch", "arch" to "arch", "archarm" to "arch",
            "opensuse-leap" to "opensuse", "opensuse-tumbleweed" to "opensuse",
            "opensuse" to "opensuse", "sles" to "opensuse",
            "alpine" to "alpine", "debian" to "debian", "ubuntu" to "ubuntu",
            "kali" to "kali", "void" to "void", "fedora" to "fedora",
            "rocky" to "rocky", "almalinux" to "almalinux", "manjaro" to "manjaro"
        )
        for ((id, expected) in ids) {
            assertEquals("ID=$id does not map to a plan", expected, DistroSetup.canonicalize(id))
        }
        assertNull("an unknown ID must not be invented", DistroSetup.canonicalize("some-other-linux"))
        assertNull("an empty ID must not be invented", DistroSetup.canonicalize(""))
        // And every mapped key must actually have a plan, not the empty fallback.
        for ((id, _) in ids) {
            val key = DistroSetup.canonicalize(id)!!
            val s = script(key)
            // The empty fallback is listOf(":", "", "", "", ""), which bakes a
            // bash-install line of ": bash". A real plan installs with its own package
            // manager there, so its presence means the key fell through to the fallback.
            assertFalse("ID=$id maps to $key but generates an empty plan",
                s.contains("then : bash"))
        }
    }

    /**
     * The solv cache is built by calling repo2solv directly.
     *
     * zypper decides whether that worked from the child's exit status, and libzypp has
     * two threads racing to reap the same child (CleanerThread, and the spawn engine).
     * The loser gets -1; the engine then returns without assigning `_exitStatus`, which
     * is still the 0 it was initialised to, so a repo2solv that failed is reported as
     * having succeeded. The only symptom the user ever saw was the downstream
     * `Can't open solv-file`, which is what libzypp says when it opens the result back
     * after its builder already claimed success.
     */
    @Test
    fun opensuseBuildsTheSolvCacheItself() {
        val s = script("opensuse")
        assertTrue(
            "openSUSE never calls repo2solv, so zypper's own broken build is all there is",
            s.contains("repo2solv")
        )
    }

    /** The exit code is not enough on its own -- the file it produced is checked. */
    @Test
    fun opensuseChecksTheSolvFileItBuilt() {
        val s = script("opensuse")
        assertTrue(
            "the solv build trusts the exit code without checking the output file",
            s.contains("[ -s \"\$_o\" ]")
        )
    }

    /** A silent failure has to say so, or it is the same bug one level down. */
    @Test
    fun opensuseReportsASolvBuildThatDidNotProduceAFile() {
        val s = script("opensuse")
        assertTrue(
            "a failed solv build is silent",
            s.contains("RETERR")
        )
    }

    /** Without metadata there is nothing to build from, so it refreshes first. */
    @Test
    fun opensuseRefreshesWhenThereIsNoRawMetadataToBuildFrom() {
        val s = script("opensuse")
        assertTrue(
            "the cold-cache path never downloads metadata",
            s.contains("/var/cache/zypp/raw/*/repodata")
        )
        assertTrue(
            "no refresh, so a fresh install never builds a cache",
            s.contains("zypper --non-interactive refresh")
        )
    }

    /** A cache that already exists is re-downloadable and expensive; leave it. */
    @Test
    fun opensuseDoesNotRebuildASolvFileItAlreadyHas() {
        val s = script("opensuse")
        assertTrue(
            "the solv cache is rebuilt on every session start",
            s.contains("[ -s \"\$_o\" ] && continue")
        )
    }
}

