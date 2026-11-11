package com.redtermapp.ui

import android.content.Context
import com.redtermapp.util.AppLog
import com.redtermapp.util.SshClient
import java.io.File

/**
 * Reaps the standalone `ssh -N` processes a dead app left behind.
 *
 * A forward or tunnel is a child of the app, and Android's process death does
 * not reach it: it is reparented to init and keeps holding its device port.
 * The next cold start therefore has to find it, kill it, and re-open what the
 * user asked to stay open — or the forwarded port is quietly dead while the
 * last app process the user saw was "running fine".
 *
 * Only same-uid processes are visible in /proc, and every candidate here is
 * the app's own child, so the kill needs no privilege. The matching and the
 * kill command are pure and unit-tested; the /proc walk and the exec are the
 * two things that may only be trusted by running them.
 */
object OrphanSweeper {

    /**
     * Whether [cmdline] is one of ours.
     *
     * The launcher script `exec`s proot, so the surviving process's argument
     * vector is `proot ... -r <rootfs> ... /bin/ssh -N ...`. A distro session
     * carries no `-N`, an SFTP connection says `-s ... sftp`, and a mosh
     * client is the mosh binary — `-N` next to *this* rootfs is what makes it
     * a standalone tunnel or forward, and nothing else.
     */
    fun isOrphan(cmdline: String, rootfsPath: String): Boolean =
        cmdline.contains("-r $rootfsPath") &&
            cmdline.contains("/bin/ssh") &&
            cmdline.contains(" -N ")

    /** The pids from [cmdlines] that are ours, never our own. */
    fun scan(cmdlines: Map<Int, String>, rootfsPath: String, selfPid: Int): List<Int> =
        cmdlines
            .filter { (pid, cmdline) -> pid != selfPid && isOrphan(cmdline, rootfsPath) }
            .keys
            .sorted()

    /** The `sh -c` line that kills [pids]; empty when there is nothing to kill. */
    fun killCommand(pids: List<Int>): String =
        if (pids.isEmpty()) "" else "kill -9 " + pids.joinToString(" ")

    /**
     * One pass: kill the orphaned `ssh -N` and clear the stale launcher
     * scripts.
     *
     * Runs only on a cold start, before any forward or tunnel is resumed, so
     * at that moment there is no live in-process forward whose script must be
     * kept — every `forward-*.sh` and `tunnel-*.sh` still on disk belongs to
     * the dead process.
     */
    fun sweep(context: Context) {
        val rootfsPath = SshClient.rootfs(context).absolutePath
        val pids = scan(procCmdlines(), rootfsPath, android.os.Process.myPid())
        val command = killCommand(pids)
        if (command.isNotEmpty()) {
            runCatching { ProcessBuilder("/system/bin/sh", "-c", command).start() }
        }
        val stale = context.filesDir.listFiles()
            ?.filter { it.name.endsWith(".sh") &&
                (it.name.startsWith("forward-") || it.name.startsWith("tunnel-")) }
        val removed = stale?.count { it.delete() } ?: 0
        AppLog.i(
            context, "port-forward",
            "orphan sweep: killed ${pids.size} ssh -N process(es), cleared $removed stale script(s)"
        )
    }

    /**
     * The pids and cmdlines visible to this uid.
     *
     * /proc on Android lists a process's own uid only, which is exactly the
     * set that can be an orphan of ours; an unreadable entry is skipped, not
     * an error, because a sweep that throws has not killed anything.
     */
    private fun procCmdlines(): Map<Int, String> {
        val proc = File("/proc")
        val names = proc.list() ?: return emptyMap()
        val result = HashMap<Int, String>()
        for (name in names) {
            val pid = name.toIntOrNull() ?: continue
            val bytes = runCatching { File(proc, "$name/cmdline").readBytes() }.getOrNull() ?: continue
            result[pid] = bytes.toString(Charsets.UTF_8).replace('\u0000', ' ')
        }
        return result
    }
}
