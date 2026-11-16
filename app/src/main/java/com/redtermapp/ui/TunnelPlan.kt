package com.redtermapp.ui

/**
 * Which servers a tunnel manager should show, and what it will actually open.
 *
 * Pure so it is unit-tested: a server is eligible only when it has at least
 * one forward that is both enabled and usable (non-blank, no whitespace, the
 * way ssh's `-L` reads it). Showing a server that only carries blank or
 * disabled forwards would hand the user a toggle that does nothing.
 */
object TunnelPlan {

    /** Servers that would open at least one local forward on connect. */
    fun serversWithTunnels(servers: List<SshStore.Server>): List<SshStore.Server> =
        servers.filter { activeSpecs(it).isNotEmpty() }

    /** The forward specs that would be passed to `ssh -L`, in the order stored. */
    fun activeSpecs(server: SshStore.Server): List<String> =
        server.forwards.filter { it.enabled && it.usable }.map { it.spec }

    /** Every forward on a server, enabled or not, for a full read-out. */
    fun allSpecs(server: SshStore.Server): List<String> =
        server.forwards.filter { it.usable }.map { it.spec }
}
