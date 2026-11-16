package com.redtermapp.util

/**
 * What a UDP reachability probe told us, and what it means for a mosh session.
 *
 * mosh needs a live UDP path to the server's port range; the app can neither
 * open one nor confirm it directly, because mosh-server only answers a
 * correctly-keyed mosh hello. The one thing a phone *can* observe is the
 * kernel's reaction to a datagram aimed at a port that is surely closed: a
 * host whose firewall admits UDP answers with ICMP "port unreachable", a
 * host (or carrier) that swallows UDP answers with nothing. That asymmetry
 * is the whole signal, and it is why the verdict is phrased as "likely" —
 * a network that drops ICMP too reads as blocked when UDP is in fact open.
 *
 * The classification and the wording of the remedy are pure, so they are
 * unit-tested; only the datagram send itself touches the network.
 */
object MoshUdpProbe {

    /** The three things a probe can observe. */
    sealed class Signal {
        /** The host answered "no such port": UDP reached it. */
        object PortUnreachable : Signal()
        /** The network or host gave up before an answer: UDP likely dropped. */
        object NoAnswer : Signal()
        /** The probe could not be run at all (no route, no socket). */
        object Unknown : Signal()
    }

    /** What the signal means for getting mosh to work. */
    enum class Verdict { UDP_OPEN, UDP_BLOCKED, INCONCLUSIVE }

    /** Map an observation to a verdict. */
    fun classify(signal: Signal): Verdict = when (signal) {
        Signal.PortUnreachable -> Verdict.UDP_OPEN
        Signal.NoAnswer -> Verdict.UDP_BLOCKED
        Signal.Unknown -> Verdict.INCONCLUSIVE
    }

    /**
     * The port to probe: one outside mosh-server's default 60000-61000 range,
     * so it is closed even while a mosh session holds a port in that range.
     */
    const val CLOSED_PROBE_PORT = 65000

    /**
     * The words the user can act on. This is the whole feature: a silent
     * mosh session and a working one look identical until someone says which
     * of the two fixes applies, and the fix that works for a blocked path is
     * not the one that works for an open one.
     */
    fun suggestion(verdict: Verdict, host: String): String = when (verdict) {
        Verdict.UDP_OPEN ->
            "UDP to $host appears open, so mosh's silence is not a firewall " +
            "dropping it: the server may lack mosh-server, or the port it " +
            "bound is not the one the phone can reach. Plain SSH works either way."
        Verdict.UDP_BLOCKED ->
            "UDP to $host appears blocked, which is the common mosh failure: " +
            "the firewall admits TCP 22 and drops the rest. A tunnel that " +
            "carries UDP (Tailscale, WireGuard) makes the same server work; " +
            "until then plain SSH is the working session."
        Verdict.INCONCLUSIVE ->
            "The UDP probe could not be run, so this stays a guess: if $host " +
            "drops ICMP as well as UDP, an open path still reads as blocked. " +
            "Plain SSH works regardless; a UDP-carrying tunnel makes mosh possible."
    }
}
