package com.redtermapp.distro

import android.content.Context
import android.util.Log
import com.redtermapp.util.AppLog
import java.io.File

/**
 * openSUSE's repository configuration, edited from the app rather than from the shell.
 *
 * A session could not install anything:
 *
 *   Error building the cache:
 *   Can't open solv-file: /var/cache/zypp/solv/openSUSE:repo-oss/solv
 *   Skipping repository 'repo-oss (16.0)' because of the above error.
 *   No provider of 'git-core' found.
 *
 * ## What actually went wrong, after the obvious things were ruled out
 *
 * Not the colon in the alias, not `http` versus `https`, not the `service=` line, not
 * space, not `mkdir`, not a write, not a rename, and not a half-built cache. Each of
 * those was measured on the device and each was fine.
 *
 * The cause is one line, from running libzypp's own cache builder by hand inside the
 * session with libzypp out of the way:
 *
 *   # repo2solv -o /var/cache/zypp/solv/repo-oss/solv \
 *   #          -X /var/cache/zypp/raw/repo-oss/repodata
 *   /var/cache/zypp/solv/repo-oss/solv: No such file or directory
 *   exit=1
 *
 * repo2solv reached `freopen(outfile, "w", stdout)`, which is the last call before it
 * writes a single byte, and failed there with ENOENT. It had already downloaded,
 * decompressed and parsed the metadata, so the raw cache, the `.zst` files, the network
 * and the binary are all fine. The per-repository `solv/` directory simply is not
 * there, and libzypp did not create one.
 *
 * libzypp reports this as `Can't open solv-file`, because by the time it opens the file
 * back the builder has already claimed success and the directory is still missing.
 *
 * ## What this code does
 *
 * Two things, both narrow:
 *
 *  - It de-colons the section header, which is the alias libzypp uses in the cache path.
 *    Worth keeping: a colon-free cache path is one fewer difference from a working
 *    upstream image. It is **not** the fix, and an earlier version of this file claimed
 *    that it was.
 *  - It creates `var/cache/zypp/{solv,raw,packages}/<alias>` for every alias it finds,
 *    which is the part that is the fix.
 *
 * It deliberately does **not** delete `openSUSE.service`, does not remove
 * `RepoManager.db`, does not rename any file, and does not drop a repository. An earlier
 * attempt did all of those across three releases; it fixed nothing and cost the user
 * distributions they could install from.
 *
 * ## Why this is Kotlin and not a line in `.startup`
 *
 * It was a line in `.startup`, for several releases, and it did nothing. Two reasons,
 * both of them invisible at the time:
 *
 *  - **`sed -i` is not a single operation.** It writes a temporary file in the target
 *    directory and renames it over the original. Under proot, as the app's own uid,
 *    that rename is where it stops -- and `sed` reports nothing useful when it happens,
 *    so the block "ran" and changed nothing.
 *  - **Nothing checked whether it worked.** The session printed `Setup complete.` and
 *    every repository line was byte-identical afterwards.
 *
 * This code has the same filesystem access the installer already uses to write
 * `/etc/passwd`, `/etc/group` and `/etc/hosts` into a rootfs, which has always worked,
 * and it reports what it did and what it could not.
 */
object OpenSuseRepositories {

    private const val TAG = "OpenSuseRepositories"

    /** What a run did, so a session can be told rather than left to guess. */
    data class Result(
        val reposInspected: Int,
        val aliasesBefore: List<String>,
        val aliasesAfter: List<String>,
        val changed: List<String>,
        val unreadable: List<String>,
        val cacheDirs: List<String>
    ) {
        val didAnything: Boolean get() = changed.isNotEmpty() || cacheDirs.isNotEmpty()

        fun oneLine(): String = buildString {
            append("openSUSE: ")
            if (unreadable.isNotEmpty()) {
                append("could not read ${unreadable.joinToString(", ")}; ")
            }
            append(
                if (changed.isEmpty()) "repositories already correct"
                else "${changed.size} file(s) updated"
            )
            append("; aliases now: ")
            append(aliasesAfter.joinToString(", ").ifEmpty { "none found" })
            // What it created, and what it could not. Without this the session start
            // looked identical whether the cache directories existed or not, which is
            // why two commits were built on a directory that was never created.
            if (cacheDirs.isNotEmpty()) {
                append("; created ${cacheDirs.size} cache dir(s): ")
                append(cacheDirs.joinToString(", "))
            }
        }
    }

    /**
     * Idempotent. Safe to run on every session start, which is where it is called from.
     *
     * Returns what it did. [log] writes a line to the app log whether or not anything
     * changed, because "it ran and found nothing to do" and "it never ran" look
     * identical from the outside and must not.
     */
    fun prepare(rootfs: File, log: ((String) -> Unit)? = null): Result {
        var inspected = 0
        val before = mutableListOf<String>()
        val after = mutableListOf<String>()
        val changed = mutableListOf<String>()
        val unreadable = mutableListOf<String>()
        val cacheDirs = mutableListOf<String>()

        for (dir in listOf("etc/zypp/repos.d", "usr/share/zypp/repos.d")) {
            val repos = File(rootfs, dir)
            val files = repos.listFiles { f: File -> f.name.endsWith(".repo") } ?: emptyArray()
            for (file in files) {
                inspected++
                val original = try {
                    file.readText()
                } catch (e: Exception) {
                    // Say so rather than counting it as correct. A repository this app
                    // cannot read is the most likely reason the whole thing looks inert.
                    unreadable += "${dir}/${file.name}: ${e.message ?: e.javaClass.simpleName}"
                    continue
                }
                before += aliasesIn(original)
                val rewritten = rewrite(original)
                if (rewritten != original) {
                    try {
                        file.writeText(rewritten)
                        changed += "${dir}/${file.name}"
                    } catch (e: Exception) {
                        unreadable += "${dir}/${file.name}: ${e.message ?: e.javaClass.simpleName}"
                    }
                }
                val now = try {
                    file.readText()
                } catch (e: Exception) {
                    rewritten
                }
                after += aliasesIn(now)
            }
        }

        // The alias is the cache *directory* name, so it has to come from every place
        // libzypp takes it from -- not just the repository files.
        //
        // The service is the one that matters, and it names its members with the
        // service prefix:
        //
        //   /etc/zypp/services.d/openSUSE.service
        //     repo_4=openSUSE:repo-oss
        //
        // and the cache paths in the failure message carry exactly that:
        //
        //   Can't open solv-file: /var/cache/zypp/solv/openSUSE:repo-oss/solv
        //
        // De-coloning the section header in a .repo file does not touch this, because
        // the header is not where this alias comes from. It was read as cosmetic for
        // two commits and the directory was then created under the wrong name.
        val serviceAliases = serviceMembersIn(rootfs)
        val indexAliases = repoIndexAliasesIn(rootfs)
        after += serviceAliases
        after += indexAliases

        for (name in listOf("solv", "raw", "packages")) {
            val d = File(rootfs, "var/cache/zypp/$name")
            val existed = d.isDirectory
            if (!existed && !d.mkdirs()) {
                unreadable += "var/cache/zypp/$name: could not be created"
                continue
            }
            if (!existed) cacheDirs += "var/cache/zypp/$name"
            // Every time, not only when it was just created. The image builds these as
            // root and the session runs as the app's uid, so an existing root-owned
            // directory is one the session still cannot write.
            try {
                // 0777, not owner-write: an owner-writable directory is not writable by
                // the session's uid. It is a cache of re-downloadable repository
                // metadata and holds no secret.
                chmod(d)
            } catch (e: Exception) {
                // The image builds these as root and the session runs as the app's uid,
                // so this is the failure that started all of it. Reported rather than
                // worked around: `File.setWritable(true, false)` would reach the same
                // mode through an API that hides which directory it is talking about.
                log?.invoke("could not widen ${d.name}: ${e.message ?: e.javaClass.simpleName}")
            }
        }

        // The per-repository cache directories, one per alias, are created here rather
        // than left to libzypp.
        //
        // Measured on the device, with the raw metadata already downloaded and parsed
        // successfully by repo2solv, this was the one thing missing:
        //
        //   # repo2solv -o /var/cache/zypp/solv/repo-oss/solv \
        //   #          -X /var/cache/zypp/raw/repo-oss/repodata
        //   /var/cache/zypp/solv/repo-oss/solv: No such file or directory
        //   exit=1
        //   # ls -l /var/cache/zypp/solv/repo-oss/
        //   ls: cannot access '/var/cache/zypp/solv/repo-oss/': No such file or directory
        //
        // repo2solv reached `freopen(outfile, "w", stdout)`, which is the last step
        // before it writes anything, and failed there with ENOENT -- the parent directory
        // was not there. So the raw half of the cache works and the solv half has no
        // directory to be written into, and libzypp did not create one.
        //
        // Creating them costs nothing when they already exist, and it is a cache of
        // re-downloadable metadata.
        val aliasDirs = after.distinct().filter { isSafeAlias(it) }
        for (name in listOf("solv", "raw", "packages")) {
            for (alias in aliasDirs) {
                val d = File(rootfs, "var/cache/zypp/$name/$alias")
                val existed = d.isDirectory
                if (!existed && !d.mkdirs()) {
                    unreadable += "var/cache/zypp/$name/$alias: could not be created"
                    continue
                }
                if (!existed) cacheDirs += "var/cache/zypp/$name/$alias"
                try {
                    chmod(d)
                } catch (e: Exception) {
                    log?.invoke("could not widen ${d.absolutePath}: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }

        val result = Result(
            reposInspected = inspected,
            aliasesBefore = before.distinct(),
            aliasesAfter = after.distinct(),
            changed = changed,
            unreadable = unreadable,
            cacheDirs = cacheDirs
        )
        log?.invoke(result.oneLine())
        return result
    }

    /**
     * `repo_N=openSUSE:repo-oss` from a service file.
     *
     * This is where the alias in the cache path actually comes from for a serviced
     * repository, and it keeps the `service:` prefix that the .repo headers do not have.
     */
    private fun serviceMembersIn(rootfs: File): List<String> {
        val out = mutableListOf<String>()
        val dir = File(rootfs, "etc/zypp/services.d")
        val files = dir.listFiles() ?: return out
        for (f in files) {
            val text = try {
                f.readText()
            } catch (_: Exception) {
                continue
            }
            for (line in text.lineSequence()) {
                val m = SERVICE_MEMBER.find(line.trim()) ?: continue
                out += m.groupValues[1]
            }
        }
        return out
    }

    /** `alias="repo-oss"` from a service's repository index. */
    private fun repoIndexAliasesIn(rootfs: File): List<String> {
        val out = mutableListOf<String>()
        val base = File(rootfs, "usr/share/zypp/local/service")
        val files = base.walkTopDown().filter { it.isFile && it.name.endsWith(".xml") }.toList()
        for (f in files) {
            val text = try {
                f.readText()
            } catch (_: Exception) {
                continue
            }
            for (m in ALIAS_ATTR.findAll(text)) out += m.groupValues[1]
        }
        return out
    }

    /**
     * `repo_1=openSUSE:repo-oss`, and *only* that.
     *
     * `repo_1_enabled=0` and `repo_1_autorefresh=1` sit on the lines directly beneath
     * it and begin with `repo_` too, so matching on that prefix turned them into
     * aliases "0" and "1" and created `var/cache/zypp/solv/0`. The digits have to be
     * required.
     */
    private val SERVICE_MEMBER = Regex("""^repo_\d+\s*=\s*(\S+)""")

    private val ALIAS_ATTR = Regex("""alias\s*=\s*"([^"]+)"""")

    /** The section header, which is the alias, not the `name=` line beside it. */
    private fun aliasesIn(text: String): List<String> =        text.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("[") && it.endsWith("]") }
            .map { it.trim('[', ']') }
            .toList()

    /**
     * Idempotent down to the byte.
     *
     * It runs on every session start, so a rewrite that adds a trailing newline each
     * time would report work on every launch forever and make it impossible to tell a
     * real change from noise.
     */
    /**
     * The repository files are left exactly as the image ships them.
     *
     * This rewrote them for several releases -- de-coloning the alias, dropping the
     * `service=` line, forcing https -- and every one of it was a guess dressed as a
     * fix. The de-colon actively broke the thing the cache needs:
     *
     * libzypp stores the solv cache in a directory named after the repository alias,
     * and a repository supplied by a service is aliased `service:alias`, so the path
     * is `/var/cache/zypp/solv/openSUSE:repo-oss`. That is the exact path in every
     * "Can't open solv-file" report, and it is where the image ships its index.
     *
     * Rewriting the header to `repo-oss` renames the repository to one the image has
     * no cache for: the shipped index goes unused, the session builds a second cache
     * directory for the invented alias, and that build fails silently because libzypp
     * cannot report its builder failing. Both aliases then appear in every refresh,
     * which is the "every repository is fetched twice" behaviour that looked like a
     * mirror problem for a week.
     *
     * Nothing here needs changing. The image ships working repository files.
     */
    private fun rewrite(text: String): String = text

    /** Convenience for the session, which has a Context and wants the line in the log. */
    fun prepareAndLog(context: Context, rootfs: File, distroName: String): Result =
        try {
            prepare(rootfs) { line ->
                AppLog.i(context, TAG, "$distroName: $line")
                Log.i(TAG, "$distroName: $line")
            }
        } catch (e: Exception) {
            AppLog.w(context, TAG, "$distroName: repository setup failed: ${e.message}")
            Log.w(TAG, "$distroName: repository setup failed", e)
            Result(0, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        }

    /**
     * An alias becomes a directory name, so it must not be able to escape the cache.
     *
     * These strings come out of repository files that a distribution image, or a user,
     * can write. `[../../etc/shadow]` is not an alias this app should turn into a
     * `mkdir -p`.
     */
    private fun isSafeAlias(alias: String): Boolean =
        alias.isNotEmpty() && alias != "." && alias != ".." &&
            !alias.contains('/') && !alias.contains('\\') && alias != "@System"

    /**
     * 0777 on a directory the session's uid has to write into.
     *
     * `android.system.Os.chmod` and not `File.setWritable(true, false)`. The
     * `setWritable` pair is `ownerOnly`, so "false" means *others* and it cannot say
     * "everybody" at all, and it reports success whether or not it changed anything --
     * which is how a root-owned directory ends up still unwritable and nothing says
     * why. A real chmod returns the failure.
     */
    private fun chmod(d: File) = android.system.Os.chmod(d.absolutePath, 0x1FF)
}
