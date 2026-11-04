package com.redtermapp.util.api

import android.content.Context
import com.redtermapp.util.AppLog
import com.redtermapp.util.api.ApiActions.ACTION
import java.io.File

/**
 * The guest-side command bridge: [ensureInstalled] extracts the static
 * helper binary once per ABI, and [installGuestScripts] lays the
 * `termux-*` scripts into the rootfs so a shell can run them. Each
 * script turns its flags into the fixed extra sequence its feature reads;
 * the helper carries them to the app over the abstract socket.
 */
object ApiHelper {

    const val GUEST_SCRIPT_VERSION = 7

    /** The helper's fixed basename, both in app storage and at the bound guest path. */
    const val BINARY_NAME = "redterm-api"

    /** The host directory is bound to this rootfs path; the executable is its [BINARY_NAME] member. */
    const val GUEST_BIND_TARGET = "/usr/local/redterm"

    private const val EXEC_PATH = "$GUEST_BIND_TARGET/$BINARY_NAME"

    /**
     * The ownership line every RedTerm-managed guest script carries. A file
     * in the guest bin that lacks it is not ours: [installOne] leaves it
     * in place rather than destroying a user-written script.
     */
    const val MARKER = "# managed by RedTerm"

    /** The app-storage directory the helper binary lives in; features that need a stable scratch file keep it here. */
    fun helperDir(context: Context): File = File(context.filesDir, "api")

    /**
     * Extract the helper binary into app storage and return the directory
     * to bind. The binary is always named `redterm-api`, because that is
     * the path every guest script execs (`/usr/local/redterm/redterm-api`);
     * the ABI and version live in a separate stamp file. A missing or
     * stale stamp re-extracts. Returns null when extraction is impossible,
     * which makes the bridge unavailable but never breaks the launch.
     */
    /**
     * The asset directory name for this device. `System.getProperty("os.arch")`
     * reports `armv7l` on a 32-bit arm device while the shipped asset is
     * named `armv7a`, so the two vocabularies have to be joined by hand;
     * `SUPPORTED_ABIS` is the fallback when the property is absent.
     */
    private fun assetArch(): String {
        val osArch = System.getProperty("os.arch")
        if (osArch != null) {
            when (osArch) {
                "aarch64" -> return "aarch64"
                "armv7a", "armv7l" -> return "armv7a"
                "x86_64" -> return "x86_64"
                "i686", "x86" -> return "i686"
            }
        }
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull()
        return when (abi) {
            "arm64-v8a" -> "aarch64"
            "armeabi-v7a" -> "armv7a"
            "x86_64" -> "x86_64"
            else -> "i686"
        }
    }

    /**
     * Extract the helper binary into app storage and return the directory
     * to bind. The binary is always named [BINARY_NAME], because that is
     * the path every guest script execs (`/usr/local/redterm/redterm-api`);
     * the ABI and version live in a separate stamp file. A missing or
     * stale stamp re-extracts. Returns null when extraction is impossible,
     * which makes the bridge unavailable but never breaks the launch.
     */
    fun ensureInstalled(context: Context): File? {
        val arch = assetArch()
        val hostDir = File(context.filesDir, "api")
        val binary = File(hostDir, BINARY_NAME)
        val stamp = File(hostDir, "stamp")
        val expected = "$arch:$GUEST_SCRIPT_VERSION"
        if (binary.isFile && runCatching { stamp.readText() }.getOrNull()?.trim() == expected) {
            return hostDir
        }
        // Only the extracted helper and its stamp are re-created: this
        // directory also holds feature output (recordings, clipboard
        // hand-offs) that outlives a re-extraction.
        runCatching { binary.delete() }
        runCatching { stamp.delete() }
        return try {
            if (!hostDir.mkdirs() && !hostDir.isDirectory) {
                AppLog.e(context, "api-helper", "could not create ${hostDir.absolutePath}")
                return null
            }
            context.assets.open("redterm-api/$arch/redterm-api").use { input ->
                binary.outputStream().use { output -> input.copyTo(output) }
            }
            binary.setExecutable(true, false)
            stamp.writeText(expected)
            AppLog.i(context, "api-helper", "helper extracted: ${binary.absolutePath} ($expected)")
            hostDir
        } catch (e: Exception) {
            AppLog.e(context, "api-helper", "helper install failed: ${e.javaClass.simpleName}: ${e.message} (arch $arch)")
            null
        }
    }

    /**
     * Writes the guest scripts into `usr/local/bin`; the helper binary
     * itself arrives through the bind. Rewritten only when the content
     * differs, so an unchanged install is a no-op. Failures go to the app
     * log: a script that never lands is a command that can never run,
     * and Diagnostics is the place that is checked when it does not.
     */
    fun installGuestScripts(context: Context?, rootfsDir: File): Boolean {
        val binDir = File(rootfsDir, "usr/local/bin")
        if (!binDir.mkdirs() && !binDir.isDirectory) {
            context?.let { AppLog.e(it, "api-helper", "script dir ${binDir.absolutePath} is not writable") }
            return false
        }
        var ok = true
        for ((name, body) in guestScripts + pureGuestScripts) {
            ok = installOne(binDir, name, body, context) && ok
        }
        return ok
    }

    private fun installOne(binDir: File, name: String, body: String, context: Context?): Boolean {
        val script = File(binDir, name)
        val current = runCatching { script.readText() }.getOrNull()
        // Ownership runs through the marker: only a file RedTerm wrote may be
        // replaced. A user-written script that happens to carry this name is
        // left in place — destroying it is how a hand-written .bashrc gets
        // wiped, and a marker is what makes a file ours in the first place.
        if (current != null && !current.contains(MARKER)) {
            context?.let { ctx -> AppLog.w(ctx, "api-helper", "script $name is not managed by RedTerm; left in place") }
            return true
        }
        return if (current != body) {
            runCatching {
                script.writeText(body)
                script.setExecutable(true, false)
            }.onFailure { cause ->
                context?.let { ctx -> AppLog.e(ctx, "api-helper", "script $name not written: ${cause.message}") }
            }.isSuccess
        } else if (!script.canExecute()) {
            runCatching { script.setExecutable(true, false) }
                .onFailure { cause ->
                    context?.let { ctx -> AppLog.e(ctx, "api-helper", "script $name not executable: ${cause.message}") }
                }.isSuccess
        } else {
            true
        }
    }

    /**
     * Wraps a script body with the shebang, the managed marker, and a guard
     * that turns a broken helper install into a readable error instead of
     * the shell's bare `not found`. The test is existence, not `-x`: the
     * helper lives on app storage, which is `noexec`, so the kernel's
     * execute-bit check fails even for a file proot runs just fine through
     * its `-L` loader. A `-x` guard would therefore veto every command on
     * every device — measured, not assumed.
     */
    private fun script(body: String): String =
        "#!/bin/sh\n" +
            "$MARKER (redterm-api bridge v$GUEST_SCRIPT_VERSION); do not edit\n" +
            "if [ ! -e $EXEC_PATH ]; then\n" +
                "  echo \"$EXEC_PATH: the RedTerm API helper is not installed in this session\" 1>&2\n" +
                "  echo \"restart the RedTerm session; if it still fails, open RedTerm Diagnostics\" 1>&2\n" +
                "  exit 127\n" +
            "fi\n" +
            "$body\n"

    /**
     * Base name to script body. Each body is installed twice: as
     * `redterm-<name>` (RedTerm's own command) and as `termux-<name>`, so
     * a script already written for Termux:API keeps working unchanged.
     */
    private val scriptBodies: Map<String, String> = mapOf(
            "api" to ("exec $EXEC_PATH \"\$@\""),
            "download" to (
                "url=; desc=; title=; path=\n" +
                    "while [ \$# -gt 0 ]; do\n" +
                    "  case \"\$1\" in\n" +
                    "    -d|--description) desc=\"\$2\"; shift 2;;\n" +
                    "    -t|--title) title=\"\$2\"; shift 2;;\n" +
                    "    -p|--path) path=\"\$2\"; shift 2;;\n" +
                    "    -h) exit 0;;\n" +
                    "    *) url=\"\$1\"; shift;;\n" +
                    "  esac\n" +
                    "done\n" +
                    "if [ -z \"\$url\" ]; then echo \"download: no url specified\"; exit 1; fi\n" +
                    "exec $EXEC_PATH --user 0 -a $ACTION --es action download --es url \"\$url\" --es description \"\$desc\" --es title \"\$title\" --es path \"\$path\""
            ),
            "battery-status" to ("exec $EXEC_PATH --user 0 -a $ACTION --es action battery-status"),
            "volume" to (
                "op=print; stream=; value=-1\n" +
                    "while [ \$# -gt 0 ]; do\n" +
                    "  case \"\$1\" in\n" +
                    "    -d|--lower) op=lower; shift;;\n" +
                    "    -i|--raise) op=raise; shift;;\n" +
                    "    -p|--print) op=print; shift;;\n" +
                    "    -m|--mute) op=mute; shift;;\n" +
                    "    -u|--unmute) op=unmute; shift;;\n" +
                    "    -s) stream=\"\$2\"; shift 2;;\n" +
                    "    -f) op=set; value=\"\$2\"; shift 2;;\n" +
                    "    -h) exit 0;;\n" +
                    "    *) shift;;\n" +
                    "  esac\n" +
                    "done\n" +
                    "exec $EXEC_PATH --user 0 -a $ACTION --es action volume --es op \"\$op\" --es stream \"\$stream\" --ei value \"\$value\""
            ),
            "vibrate" to (
                "dur=500; pat=\n" +
                    "while [ \$# -gt 0 ]; do\n" +
                    "  case \"\$1\" in\n" +
                    "    -d|--duration) dur=\"\$2\"; shift 2;;\n" +
                    "    -P|--pattern) pat=\"\$2\"; shift 2;;\n" +
                    "    -h) exit 0;;\n" +
                    "    *) shift;;\n" +
                    "  esac\n" +
                    "done\n" +
                    "exec $EXEC_PATH --user 0 -a $ACTION --es action vibrate --ei duration \"\$dur\" --es pattern \"\$pat\""
            ),
            "notification" to (
                "op=post; title=; message=; id=-1\n" +
                    "while [ \$# -gt 0 ]; do\n" +
                    "  case \"\$1\" in\n" +
                    "    -t|--title) title=\"\$2\"; shift 2;;\n" +
                    "    -m|--message) message=\"\$2\"; shift 2;;\n" +
                    "    -i|--id) id=\"\$2\"; shift 2;;\n" +
                    "    -r|--remove) op=remove; shift;;\n" +
                    "    -c|--clear) op=clear; shift;;\n" +
                    "    -h) exit 0;;\n" +
                    "    *) shift;;\n" +
                    "  esac\n" +
                    "done\n" +
                    "exec $EXEC_PATH --user 0 -a $ACTION --es action notification --es op \"\$op\" --es title \"\$title\" --es message \"\$message\" --ei id \"\$id\""
            ),
            "notification-list" to (
                "exec $EXEC_PATH --user 0 -a $ACTION --es action notification --es op list"
            ),
            "notification-remove" to (
                "id=-1\n" +
                    "while [ \$# -gt 0 ]; do\n" +
                    "  case \"\$1\" in\n" +
                    "    -i|--id) id=\"\$2\"; shift 2;;\n" +
                    "    -h) exit 0;;\n" +
                    "    *) shift;;\n" +
                    "  esac\n" +
                    "done\n" +
                    "exec $EXEC_PATH --user 0 -a $ACTION --es action notification --es op remove --ei id \"\$id\""
            ),
            "toast" to (
                "exec $EXEC_PATH --user 0 -a $ACTION --es action toast --es message \"\$*\""
            ),
            "brightness" to (
                "if [ \$# -gt 0 ]; then\n" +
                    "  exec $EXEC_PATH --user 0 -a $ACTION --es action brightness --ei level \"\$1\"\n" +
                    "else\n" +
                    "  exec $EXEC_PATH --user 0 -a $ACTION --es action brightness\n" +
                    "fi"
            ),
            "audio-info" to ("exec $EXEC_PATH --user 0 -a $ACTION --es action audio-info"),
            "clipboard-set" to (
                "if [ \"\$1\" = \"-f\" ]; then text=\"\$(cat \"\$2\")\"; else text=\"\$*\"; fi\n" +
                    "exec $EXEC_PATH --user 0 -a $ACTION --es action clipboard --es op set --es text \"\$text\""
            ),
            "clipboard-get" to ("exec $EXEC_PATH --user 0 -a $ACTION --es action clipboard --es op get"),
            "network-cidrs" to ("exec $EXEC_PATH --user 0 -a $ACTION --es action network --es op cidrs"),
            "network-interface" to ("exec $EXEC_PATH --user 0 -a $ACTION --es action network --es op interface"),
            "wifi-scaninfo" to ("exec $EXEC_PATH --user 0 -a $ACTION --es action wifi --es op scaninfo"),
            "share" to (
                "mime=; subject=; files=; text=\n" +
                    "while [ \$# -gt 0 ]; do\n" +
                    "  case \"\$1\" in\n" +
                    "    -d) mime=\"\$2\"; shift 2;;\n" +
                    "    -s) subject=\"\$2\"; shift 2;;\n" +
                    "    -f) files=\"\$2\"; shift 2;;\n" +
                    "    -h) exit 0;;\n" +
                    "    *) text=\"\${text:+\$text }\$1\"; shift;;\n" +
                    "  esac\n" +
                    "done\n" +
                    "exec $EXEC_PATH --user 0 -a $ACTION --es action share --es text \"\$text\" --es mime \"\$mime\" --es subject \"\$subject\" --esa files \"\$files\""
            ),
            "location" to (
                "if [ \$# -gt 0 ]; then\n" +
                    "  exec $EXEC_PATH --user 0 -a $ACTION --es action location --ei timeout \"\$1\"\n" +
                    "else\n" +
                    "  exec $EXEC_PATH --user 0 -a $ACTION --es action location\n" +
                    "fi"
            ),
            "sms-send" to (
                "if [ \$# -lt 2 ]; then echo \"sms-send: need number and message\"; exit 1; fi\n" +
                    "exec $EXEC_PATH --user 0 -a $ACTION --es action sms --es op send --es number \"\$1\" --es text \"\$2\""
            ),
            "sms-inbox" to (
                "if [ \$# -ge 1 ]; then\n" +
                    "  exec $EXEC_PATH --user 0 -a $ACTION --es action sms --es op inbox --ei limit \"\$1\"\n" +
                    "else\n" +
                    "  exec $EXEC_PATH --user 0 -a $ACTION --es action sms --es op inbox\n" +
                    "fi"
            ),
            "telephony-call" to (
                "if [ \$# -lt 1 ]; then echo \"telephony-call: need a number\"; exit 1; fi\n" +
                    "exec $EXEC_PATH --user 0 -a $ACTION --es action call --es number \"\$1\""
            ),
            "call" to (
                "if [ \$# -lt 1 ]; then echo \"call: need a number\"; exit 1; fi\n" +
                    "exec $EXEC_PATH --user 0 -a $ACTION --es action call --es number \"\$1\""
            ),
            "contact-list" to (
                "if [ \$# -ge 1 ]; then\n" +
                    "  exec $EXEC_PATH --user 0 -a $ACTION --es action contacts --es name \"\$1\"\n" +
                    "else\n" +
                    "  exec $EXEC_PATH --user 0 -a $ACTION --es action contacts --es name \"\"\n" +
                    "fi"
            ),
            "torch" to (
                "if [ \$# -ge 1 ]; then\n" +
                    "  exec $EXEC_PATH --user 0 -a $ACTION --es action torch --es op \"\$1\"\n" +
                    "else\n" +
                    "  exec $EXEC_PATH --user 0 -a $ACTION --es action torch --es op status\n" +
                    "fi"
            ),
            "media-scan" to (
                "rec=; verb=; files=\n" +
                    "while [ \$# -gt 0 ]; do\n" +
                    "  case \"\$1\" in\n" +
                    "    -r) rec=\"--ez recursive true\"; shift;;\n" +
                    "    -v) verb=\"--ez verbose true\"; shift;;\n" +
                    "    -h) exit 0;;\n" +
                    "    *) files=\"\$files \$1\"; shift;;\n" +
                    "  esac\n" +
                    "done\n" +
                    "if [ -z \"\$files\" ]; then echo \"media-scan: missing file argument\"; exit 1; fi\n" +
                    "PATHS=\n" +
                    "for p in \$files; do\n" +
                    "  case \"\$p\" in\n" +
                    "    *,*) p=\$(printf '%s' \"\$p\" | sed 's/,/\\\\,/g');; esac\n" +
                    "  if [ -z \"\$PATHS\" ]; then PATHS=\"\$p\"; else PATHS=\"\$PATHS,\$p\"; fi\n" +
                    "done\n" +
                    "if [ -z \"\$PATHS\" ]; then echo \"media-scan: no usable paths\"; exit 1; fi\n" +
                    "exec $EXEC_PATH --user 0 -a $ACTION --es action media-scan \$rec \$verb --esa paths \"\$PATHS\""
            ),
            "camera-info" to ("exec $EXEC_PATH --user 0 -a $ACTION --es action camera-info"),
            "call-log" to (
                "limit=10; offset=0\n" +
                    "while [ \$# -gt 0 ]; do\n" +
                    "  case \"\$1\" in\n" +
                    "    -l) limit=\"\$2\"; shift 2;;\n" +
                    "    -o) offset=\"\$2\"; shift 2;;\n" +
                    "    -h) exit 0;;\n" +
                    "    *) shift;;\n" +
                    "  esac\n" +
                    "done\n" +
                    "exec $EXEC_PATH --user 0 -a $ACTION --es action call-log --ei offset \"\$offset\" --ei limit \"\$limit\""
            ),
            "wallpaper" to (
                "file=; url=; op=set\n" +
                    "while [ \$# -gt 0 ]; do\n" +
                    "  case \"\$1\" in\n" +
                    "    -f) file=\"\$2\"; shift 2;;\n" +
                    "    -u) url=\"\$2\"; shift 2;;\n" +
                    "    -l) shift;;\n" +
                    "    -h) exit 0;;\n" +
                    "    *) shift;;\n" +
                    "  esac\n" +
                    "done\n" +
                    "if [ -n \"\$file\" ]; then exec $EXEC_PATH --user 0 -a $ACTION --es action wallpaper --es op set --es file \"\$file\";\n" +
                    "elif [ -n \"\$url\" ]; then exec $EXEC_PATH --user 0 -a $ACTION --es action wallpaper --es op set --es url \"\$url\";\n" +
                    "else exec $EXEC_PATH --user 0 -a $ACTION --es action wallpaper --es op remove; fi"
            ),
            "sensor" to (
                "sensor=; count=1; delay=200\n" +
                    "while [ \$# -gt 0 ]; do\n" +
                    "  case \"\$1\" in\n" +
                    "    -s) sensor=\"\$2\"; shift 2;;\n" +
                    "    -n) count=\"\$2\"; shift 2;;\n" +
                    "    -d) delay=\"\$2\"; shift 2;;\n" +
                    "    -h) exit 0;;\n" +
                    "    *) shift;;\n" +
                    "  esac\n" +
                    "done\n" +
                    "if [ -n \"\$sensor\" ]; then\n" +
                    "  exec $EXEC_PATH --user 0 -a $ACTION --es action sensor --es op read --es sensor \"\$sensor\" --ei limit \"\$count\" --ei delay \"\$delay\"\n" +
                    "else\n" +
                    "  exec $EXEC_PATH --user 0 -a $ACTION --es action sensor --es op list\n" +
                    "fi"
            ),
            "usb" to (
                "op=list; dev=; vid=; pid=; pos=\n" +
                    "while [ \$# -gt 0 ]; do\n" +
                    "  case \"\$1\" in\n" +
                    "    -l|--list) op=list; shift;;\n" +
                    "    -p|--permission) op=permission; shift;;\n" +
                    "    -o|--open) op=open; shift;;\n" +
                    "    -f|--file) shift 2;;\n" +
                    "    -h) exit 0;;\n" +
                    "    *) pos=\"\$pos \$1\"; shift;;\n" +
                    "  esac\n" +
                    "done\n" +
                    "set -- \$pos\n" +
                    "if [ \$op = open ]; then\n" +
                    "  if [ \$# -eq 1 ]; then dev=\"\$1\"\n" +
                    "  elif [ \$# -eq 2 ]; then vid=\"\$1\"; pid=\"\$2\"; fi\n" +
                    "fi\n" +
                    "exec $EXEC_PATH --user 0 -a $ACTION --es action usb --es op \"\$op\" --es device \"\$dev\" --es vendorId \"\$vid\" --es productId \"\$pid\""
            ),
            "tts-speak" to (
                "text=; engine=; lang=; region=; variant=; pitch=; rate=; stream=\n" +
                    "while [ \$# -gt 0 ]; do\n" +
                    "  case \"\$1\" in\n" +
                    "    -e) engine=\"\$2\"; shift 2;;\n" +
                    "    -l) lang=\"\$2\"; shift 2;;\n" +
                    "    -n) region=\"\$2\"; shift 2;;\n" +
                    "    -v) variant=\"\$2\"; shift 2;;\n" +
                    "    -p) pitch=\"\$2\"; shift 2;;\n" +
                    "    -r) rate=\"\$2\"; shift 2;;\n" +
                    "    -s) stream=\"\$2\"; shift 2;;\n" +
                    "    -h) exit 0;;\n" +
                    "    *) text=\"\${text:+\$text }\$1\"; shift;;\n" +
                    "  esac\n" +
                    "done\n" +
                    "if [ -z \"\$text\" ]; then text=\"\$(cat)\"; fi\n" +
                    "extra=;\n" +
                    "if [ -n \"\$pitch\" ]; then extra=\"\$extra --ef pitch \$pitch\"; fi\n" +
                    "if [ -n \"\$rate\" ]; then extra=\"\$extra --ef rate \$rate\"; fi\n" +
                    "exec $EXEC_PATH --user 0 -a $ACTION --es action tts --es op speak --es text \"\$text\" --es engine \"\$engine\" --es language \"\$lang\" --es region \"\$region\" --es variant \"\$variant\" --es stream \"\$stream\" \$extra"
            ),
            "tts-engines" to (
                "engine=\n" +
                    "while [ \$# -gt 0 ]; do\n" +
                    "  case \"\$1\" in\n" +
                    "    -e) engine=\"\$2\"; shift 2;;\n" +
                    "    -h) exit 0;;\n" +
                    "    *) shift;;\n" +
                    "  esac\n" +
                    "done\n" +
                    "exec $EXEC_PATH --user 0 -a $ACTION --es action tts --es op engines --es engine \"\$engine\""
            ),
            "media-player" to (
                "if [ \$# -gt 1 ] && [ \"\$1\" = play ]; then\n" +
                    "  exec $EXEC_PATH --user 0 -a $ACTION --es action media-player --es op play --es file \"\$(realpath \"\$2\")\"\n" +
                    "elif [ \$# -eq 1 ]; then\n" +
                    "  case \"\$1\" in\n" +
                    "    play) exec $EXEC_PATH --user 0 -a $ACTION --es action media-player --es op resume;;\n" +
                    "    info|pause|stop) exec $EXEC_PATH --user 0 -a $ACTION --es action media-player --es op \"\$1\";;\n" +
                    "    *) echo \"media-player: invalid cmd '\$1'\"; exit 1;;\n" +
                    "  esac\n" +
                    "else\n" +
                    "  echo \"media-player: usage: play [file] | info | pause | stop\"; exit 0\n" +
                    "fi"
            ),
            "microphone-record" to (
                "file=; limit=; encoder=; bitrate=; srate=; channels=; op=; params=0\n" +
                    "while [ \$# -gt 0 ]; do\n" +
                    "  case \"\$1\" in\n" +
                    "    -d) op=start; shift;;\n" +
                    "    -f) file=\"\$2\"; params=1; shift 2;;\n" +
                    "    -l) limit=\"\$2\"; params=1; shift 2;;\n" +
                    "    -e) encoder=\"\$2\"; params=1; shift 2;;\n" +
                    "    -b) bitrate=\"\$2\"; params=1; shift 2;;\n" +
                    "    -r) srate=\"\$2\"; params=1; shift 2;;\n" +
                    "    -c) channels=\"\$2\"; params=1; shift 2;;\n" +
                    "    -i) op=info; shift;;\n" +
                    "    -q) op=stop; shift;;\n" +
                    "    -h) exit 0;;\n" +
                    "    *) shift;;\n" +
                    "  esac\n" +
                    "done\n" +
                    "if [ -z \"\$op\" ]; then\n" +
                    "  if [ \$params = 1 ]; then op=start; else op=info; fi\n" +
                    "fi\n" +
                    "extra=;\n" +
                    "[ -n \"\$file\" ] && extra=\"\$extra --es file \$file\"\n" +
                    "[ -n \"\$limit\" ] && extra=\"\$extra --ei limit \$((limit * 1000))\"\n" +
                    "[ -n \"\$encoder\" ] && extra=\"\$extra --es encoder \$encoder\"\n" +
                    "[ -n \"\$bitrate\" ] && extra=\"\$extra --ei bitrate \$((bitrate * 1000))\"\n" +
                    "[ -n \"\$srate\" ] && extra=\"\$extra --ei srate \$srate\"\n" +
                    "[ -n \"\$channels\" ] && extra=\"\$extra --ei channels \$channels\"\n" +
                    "exec $EXEC_PATH --user 0 -a $ACTION --es action mic-record --es op \"\$op\" \$extra"
            ),
            "infrared-transmit" to (
                "freq=38000\n" +
                    "while [ \$# -gt 0 ]; do\n" +
                    "  case \"\$1\" in\n" +
                    "    -h) exit 0;;\n" +
                    "    *) freq=\"\$1\"; shift;;\n" +
                    "  esac\n" +
                    "done\n" +
                    "exec $EXEC_PATH --user 0 -a $ACTION --es action infrared --es op transmit --ei frequency \"\$freq\""
            ),
            "infrared-frequencies" to (
                "exec $EXEC_PATH --user 0 -a $ACTION --es action infrared --es op list"
            ),
            "telephony-deviceinfo" to (
                "exec $EXEC_PATH --user 0 -a $ACTION --es action telephony-deviceinfo"
            ),
            "sscid" to (
                "exec $EXEC_PATH --user 0 -a $ACTION --es action sscid --es op ssid"
            ),
            "wifi-connectioninfo" to (
                "exec $EXEC_PATH --user 0 -a $ACTION --es action sscid --es op info"
            ),
            "keystore" to (
                "cmd=list; alias=; alg=; size=; curve=; data=; sig=; detailed=;\n" +
                    "while [ \$# -gt 0 ]; do\n" +
                    "  case \"\$1\" in\n" +
                    "    list|create|sign|verify|destroy) cmd=\"\$1\"; shift;;\n" +
                    "    -a) alg=\"\$2\"; shift 2;;\n" +
                    "    -s) size=\"\$2\"; shift 2;;\n" +
                    "    -c) curve=\"\$2\"; shift 2;;\n" +
                    "    -d) data=\"\$2\"; shift 2;;\n" +
                    "    -g) sig=\"\$2\"; shift 2;;\n" +
                    "    -u) shift 2;;\n" +
                    "    --detailed) detailed=\"--ez detailed true\"; shift;;\n" +
                    "    -h) exit 0;;\n" +
                    "    *) alias=\"\$1\"; shift;;\n" +
                    "  esac\n" +
                    "done\n" +
                    "extra=;\n" +
                    "[ -n \"\$alg\" ] && extra=\"\$extra --es algorithm \$alg\"\n" +
                    "[ -n \"\$curve\" ] && extra=\"\$extra --es curve \$curve\"\n" +
                    "[ -n \"\$size\" ] && extra=\"\$extra --ei size \$size\"\n" +
                    "[ -n \"\$data\" ] && extra=\"\$extra --es data \$data\"\n" +
                    "[ -n \"\$sig\" ] && extra=\"\$extra --es signature \$sig\"\n" +
                    "exec $EXEC_PATH --user 0 -a $ACTION --es action keystore --es command \"\$cmd\" --es alias \"\$alias\" \$extra \$detailed"
            ),
            "storage-get" to (
                "if [ \$# -ne 1 ]; then echo \"storage-get: need exactly one path\"; exit 1; fi\n" +
                    "exec $EXEC_PATH --user 0 -a $ACTION --es action storage-get --es file \"\$1\""
            ),
            "saf-grant" to (
                "exec $EXEC_PATH --user 0 -a $ACTION --es action saf --es safmethod grant"
            ),
            "saf-create" to (
                "if [ \$# -lt 2 ]; then echo \"saf-create: need tree URI and filename\"; exit 1; fi\n" +
                    "exec $EXEC_PATH --user 0 -a $ACTION --es action saf --es safmethod createDocument --es treeuri \"\$1\" --es filename \"\$2\""
            ),
            "api-start" to (
                "exec $EXEC_PATH --user 0 -a $ACTION --es action api-start --es op start"
            ),
            "api-stop" to (
                "exec $EXEC_PATH --user 0 -a $ACTION --es action api-start --es op stop"
            ),
            "dialog" to (
                "widget=text; hint=; title=; values=; range=; multilines=; pass=; num=; datefmt=;\n" +
                    "while [ \$# -gt 0 ]; do\n" +
                    "  case \"\$1\" in\n" +
                    "    confirm|checkbox|counter|date|radio|sheet|spinner|text|time) widget=\"\$1\"; shift;;\n" +
                    "    -i) hint=\"\$2\"; shift 2;;\n" +
                    "    -t) title=\"\$2\"; shift 2;;\n" +
                    "    -v) values=\"\$2\"; shift 2;;\n" +
                    "    -r) range=\"\$2\"; shift 2;;\n" +
                    "    -d) datefmt=\"\$2\"; shift 2;;\n" +
                    "    -m) multilines=1; shift;;\n" +
                    "    -p) pass=1; shift;;\n" +
                    "    -n) num=1; shift;;\n" +
                    "    -h) exit 0;;\n" +
                    "    *) shift;;\n" +
                    "  esac\n" +
                    "done\n" +
                    "set -- --user 0 -a $ACTION --es action dialog --es input_method \"\$widget\"\n" +
                    "[ -n \"\$hint\" ] && set -- \"\$@\" --es input_hint \"\$hint\"\n" +
                    "[ -n \"\$title\" ] && set -- \"\$@\" --es input_title \"\$title\"\n" +
                    "[ -n \"\$values\" ] && set -- \"\$@\" --es input_values \"\$values\"\n" +
                    "[ -n \"\$range\" ] && set -- \"\$@\" --es input_range \"\$range\"\n" +
                    "[ -n \"\$datefmt\" ] && set -- \"\$@\" --es date_format \"\$datefmt\"\n" +
                    "[ \"\$multilines\" = 1 ] && set -- \"\$@\" --ez multiple_lines true\n" +
                    "[ \"\$pass\" = 1 ] && set -- \"\$@\" --ez password true\n" +
                    "[ \"\$num\" = 1 ] && set -- \"\$@\" --ez numeric true\n" +
                    "exec $EXEC_PATH \"\$@\""
            ),
            "nfc" to (
                "op=status; value=; mode=read\n" +
                    "while [ \$# -gt 0 ]; do\n" +
                    "  case \"\$1\" in\n" +
                    "    -l|--read) op=read; shift;;\n" +
                    "    -w|--write) op=write; value=\"\$2\"; shift 2;;\n" +
                    "    -e|--enable) op=enable; shift;;\n" +
                    "    -o|--disable) op=disable; shift;;\n" +
                    "    -h) exit 0;;\n" +
                    "    *) shift;;\n" +
                    "  esac\n" +
                    "done\n" +
                    "extra=;\n" +
                    "[ -n \"\$value\" ] && extra=\"--es value \$value\"\n" +
                    "exec $EXEC_PATH --user 0 -a $ACTION --es action nfc --es op \"\$op\" \$extra"
            ),
            "fingerprint" to (
                "desc=; op=check\n" +
                    "while [ \$# -gt 0 ]; do\n" +
                    "  case \"\$1\" in\n" +
                    "    -c|--check) op=check; shift;;\n" +
                    "    -s|--scan) op=scan; shift;;\n" +
                    "    -d|--description) desc=\"\$2\"; shift 2;;\n" +
                    "    -h) exit 0;;\n" +
                    "    *) shift;;\n" +
                    "  esac\n" +
                    "done\n" +
                    "extra=;\n" +
                    "[ -n \"\$desc\" ] && extra=\"--es description \$desc\"\n" +
                    "exec $EXEC_PATH --user 0 -a $ACTION --es action fingerprint --es op \"\$op\" \$extra"
            ),
            "job-scheduler" to (
                "script=; period=0; network=; battery=; storage=; charging=; persisted=; cancel=; cancel_all=; job_id=;\n" +
                    "while [ \$# -gt 0 ]; do\n" +
                    "  case \"\$1\" in\n" +
                    "    --script) script=\"\$2\"; shift 2;;\n" +
                    "    --period-ms) period=\"\$2\"; shift 2;;\n" +
                    "    --network) network=\"\$2\"; shift 2;;\n" +
                    "    --battery-not-low) battery=1; shift;;\n" +
                    "    --storage-not-low) storage=1; shift;;\n" +
                    "    --charging) charging=1; shift;;\n" +
                    "    --persisted) persisted=1; shift;;\n" +
                    "    --cancel) cancel=1; shift;;\n" +
                    "    --cancel-all) cancel_all=1; shift;;\n" +
                    "    --job-id) job_id=\"\$2\"; shift 2;;\n" +
                    "    -h) exit 0;;\n" +
                    "    *) shift;;\n" +
                    "  esac\n" +
                    "done\n" +
                    "extra=;\n" +
                    "[ -n \"\$script\" ] && extra=\"\$extra --es script \$script\"\n" +
                    "[ \"\$period\" != \"0\" ] && extra=\"\$extra --ei period_ms \$period\"\n" +
                    "[ -n \"\$network\" ] && extra=\"\$extra --es network \$network\"\n" +
                    "[ \"\$battery\" = 1 ] && extra=\"\$extra --ez battery_not_low true\"\n" +
                    "[ \"\$storage\" = 1 ] && extra=\"\$extra --ez storage_not_low true\"\n" +
                    "[ \"\$charging\" = 1 ] && extra=\"\$extra --ez charging true\"\n" +
                    "[ \"\$cancel\" = 1 ] && extra=\"\$extra --ez cancel true\"\n" +
                    "[ \"\$cancel_all\" = 1 ] && extra=\"\$extra --ez cancel_all true\"\n" +
                    "[ -n \"\$job_id\" ] && extra=\"\$extra --es job_id \$job_id\"\n" +
                    "exec $EXEC_PATH --user 0 -a $ACTION --es action job-scheduler \$extra"
            ),
            "callback" to (
                "if [ \$# -lt 1 ]; then echo \"callback: need a URL\"; exit 1; fi\n" +
                    "url=\"\$1\"; shift\n" +
                    "data=; method=POST; timeout=10\n" +
                    "while [ \$# -gt 0 ]; do\n" +
                    "  case \"\$1\" in\n" +
                    "    -d|--data) data=\"\$2\"; shift 2;;\n" +
                    "    -m|--method) method=\"\$2\"; shift 2;;\n" +
                    "    -t|--timeout) timeout=\"\$2\"; shift 2;;\n" +
                    "    -h) exit 0;;\n" +
                    "    *) shift;;\n" +
                    "  esac\n" +
                    "done\n" +
                    "exec $EXEC_PATH --user 0 -a $ACTION --es action callback --es url \"\$url\" --es data \"\$data\" --es method \"\$method\" --ei timeout \"\$timeout\""
            ),
            "camera-photo" to (
                "camera=0; file=\n" +
                    "while [ \$# -gt 0 ]; do\n" +
                    "  case \"\$1\" in\n" +
                    "    -c) camera=\"\$2\"; shift 2;;\n" +
                    "    -h) exit 0;;\n" +
                    "    *) if [ -z \"\$file\" ]; then file=\"\$1\"; fi; shift;;\n" +
                    "  esac\n" +
                    "done\n" +
                    "if [ -z \"\$file\" ]; then echo \"camera-photo: need an output file\"; exit 1; fi\n" +
                    "exec $EXEC_PATH --user 0 -a $ACTION --es action camera-photo --es camera \"\$camera\" --es file \"\$file\""
            ),
            "notification-channel" to (
                "id=; name=; delete=\n" +
                    "while [ \$# -gt 0 ]; do\n" +
                    "  case \"\$1\" in\n" +
                    "    -d|--delete) id=\"\$2\"; delete=\"--ez delete true\"; shift 2;;\n" +
                    "    -h) exit 0;;\n" +
                    "    *) if [ -z \"\$id\" ]; then id=\"\$1\"; else name=\"\$1\"; fi; shift;;\n" +
                    "  esac\n" +
                    "done\n" +
                    "if [ -z \"\$id\" ]; then echo \"notification-channel: need a channel id\"; exit 1; fi\n" +
                    "exec $EXEC_PATH --user 0 -a $ACTION --es action notification-channel --es id \"\$id\" --es name \"\$name\" \$delete"
            ),
            "saf-dirs" to (
                "exec $EXEC_PATH --user 0 -a $ACTION --es action saf --es safmethod getManagedDocumentTrees"
            ),
            "saf-ls" to (
                "if [ \$# -lt 1 ]; then echo \"saf-ls: need a tree URI\"; exit 1; fi\n" +
                    "exec $EXEC_PATH --user 0 -a $ACTION --es action saf --es safmethod listDirectory --es treeuri \"\$1\""
            ),
            "saf-read" to (
                "if [ \$# -lt 1 ]; then echo \"saf-read: need a document URI\"; exit 1; fi\n" +
                    "exec $EXEC_PATH --user 0 -a $ACTION --es action saf --es safmethod readDocument --es uri \"\$1\""
            ),
            "saf-rm" to (
                "if [ \$# -lt 1 ]; then echo \"saf-rm: need a document URI\"; exit 1; fi\n" +
                    "exec $EXEC_PATH --user 0 -a $ACTION --es action saf --es safmethod removeDocument --es uri \"\$1\""
            ),
            "saf-write" to (
                "if [ \$# -lt 1 ]; then echo \"saf-write: need a document URI\"; exit 1; fi\n" +
                    "uri=\"\$1\"; shift\n" +
                    "exec $EXEC_PATH --user 0 -a $ACTION --es action saf --es safmethod writeDocument --es uri \"\$uri\" --es data \"\$(cat)\""
            ),
            "sms-list" to (
                "limit=10; offset=0\n" +
                    "while [ \$# -gt 0 ]; do\n" +
                    "  case \"\$1\" in\n" +
                    "    -l) limit=\"\$2\"; shift 2;;\n" +
                    "    -o) offset=\"\$2\"; shift 2;;\n" +
                    "    -h) exit 0;;\n" +
                    "    *) shift;;\n" +
                    "  esac\n" +
                    "done\n" +
                    "exec $EXEC_PATH --user 0 -a $ACTION --es action sms --es op list --ei limit \"\$limit\" --ei offset \"\$offset\""
            ),
            "speech-to-text" to (
                "lang=en-US\n" +
                    "while [ \$# -gt 0 ]; do\n" +
                    "  case \"\$1\" in\n" +
                    "    -l|--language) lang=\"\$2\"; shift 2;;\n" +
                    "    -h) exit 0;;\n" +
                    "    *) shift;;\n" +
                    "  esac\n" +
                    "done\n" +
                    "exec $EXEC_PATH --user 0 -a $ACTION --es action speech-to-text --es language \"\$lang\""
            ),
            "telephony-cellinfo" to (
                "exec $EXEC_PATH --user 0 -a $ACTION --es action telephony-cellinfo"
            ),
            "wifi-enable" to (
                "if [ \$# -lt 1 ]; then echo \"wifi-enable: need true or false\"; exit 1; fi\n" +
                    "exec $EXEC_PATH --user 0 -a $ACTION --es action wifi-enable --ez enabled \"\$1\""
            ),
        )

    /** Every guest command, under both its redterm- and termux- name. */
    val guestScripts: Map<String, String> by lazy {
        buildMap {
            for ((base, body) in scriptBodies) {
                val text = script(body)
                put("redterm-$base", text)
                put("termux-$base", text)
            }
        }
    }

    /**
     * Pure guest-side commands: installed like the bridge scripts but with no
     * helper-existence guard, because they never talk to the app. RedTerm-only
     * names (no `termux-` alias) since they have no Termux:API counterpart.
     *
     * `redterm-startup-local` is the discoverability tool for issue #32: it
     * prints a safe starter for the user-managed `/root/.startup.local` hook.
     * The body is a quoted here-doc, so it carries no shell variables and no
     * distro assumption — the Debian-family dynamic-MOTD line is double-guarded
     * (`command -v run-parts` and `[ -d /etc/update-motd.d ]`), which makes it
     * a no-op on the other 19 distros, so one file is correct everywhere.
     */
    val pureGuestScripts: Map<String, String> by lazy {
        mapOf("redterm-startup-local" to STARTUP_LOCAL_SCRIPT)
    }

    private const val STARTUP_LOCAL_SCRIPT = """#!/bin/sh
# managed by RedTerm (redterm-api bridge v$GUEST_SCRIPT_VERSION); do not edit
# redterm-startup-local — print a safe starter for /root/.startup.local, the
# user-managed hook that runs once per new interactive RedTerm session.
# Pure guest-side tool: no bridge, no API helper. Create the hook with:
#
#     redterm-startup-local > /root/.startup.local
#
# The printed body is safe on every distro: its Debian-family dynamic-MOTD
# line is guarded so it is a no-op wherever run-parts or /etc/update-motd.d
# is absent. Add your own per-session lines in the marked section.
cat <<'STARTER'
#!/bin/sh
# /root/.startup.local - RedTerm user startup hook.
#
# Runs once per new interactive session, as a separate process, just before
# the interactive shell starts. RedTerm rewrites /root/.startup on every
# session but never this file, so it survives session restarts and app
# updates. A non-zero exit here is reported, not fatal: the session
# continues. Guard each line for its own tools so a missing piece is a
# no-op, not a broken session.

# Debian-family dynamic MOTD. The double guard makes this a no-op on any
# distro that does not ship run-parts or /etc/update-motd.d, so the whole
# file is safe to keep on every distribution.
if command -v run-parts >/dev/null 2>&1 && [ -d /etc/update-motd.d ]; then
    run-parts /etc/update-motd.d
fi

# --- your own per-session initialization goes below this line ---
STARTER
"""
}
