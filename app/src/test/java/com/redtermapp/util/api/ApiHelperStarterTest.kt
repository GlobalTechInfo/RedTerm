package com.redtermapp.util.api

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `redterm-startup-local` is installed into the guest under a managed marker,
 * so its text is load-bearing: the shell sources and executes it. Pin the
 * relationship between the tool and the starter it prints, so a refactor that
 * splits the two silently breaks either the hook the user writes or the tool
 * that prints it.
 */
class ApiHelperStarterTest {

    @Test
    fun `the starter carries the ownership marker and a safe Debian guard`() {
        val s = ApiHelper.startupStarter()
        assertTrue("missing the marker the GUI keys on", s.contains("RedTerm user startup hook"))
        // The MOTD line must stay double-guarded so it is a no-op off Debian.
        assertTrue(s.contains("command -v run-parts"))
        assertTrue(s.contains("[ -d /etc/update-motd.d ]"))
        assertTrue(s.startsWith("#!/bin/sh"))
    }

    @Test
    fun `the guest tool emits the same starter inside a quoted here-doc`() {
        val tool = ApiHelper.pureGuestScripts.getValue("redterm-startup-local")
        val s = ApiHelper.startupStarter()
        assertTrue(tool.startsWith("#!/bin/sh"))
        assertTrue("the tool must print the starter verbatim", tool.contains(s))
        // A quoted here-doc delimiter: no expansion, so the body is literal.
        assertTrue(tool.contains("cat <<'STARTER'"))
        assertTrue(tool.contains("STARTER"))
    }
}
