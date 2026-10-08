package com.redtermapp.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import com.redtermapp.service.TerminalService
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The live sessions, plus what each one is.
 *
 * The descriptors are held beside the sessions rather than on them because
 * `TerminalSession` comes from the terminal engine and has no field for it. They
 * are keyed by the session object itself, which uses identity equality, so the
 * pairing cannot drift when two sessions happen to share a name.
 */
class TerminalViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        @Volatile
        private var instance: TerminalViewModel? = null

        fun get(application: Application): TerminalViewModel =
            instance ?: synchronized(this) {
                instance ?: TerminalViewModel(application).also { instance = it }
            }
    }

    private val _sessions = MutableStateFlow<List<TerminalSession>>(emptyList())
    val sessions: StateFlow<List<TerminalSession>> = _sessions.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    /** Session object to what it is. Not a flow: read it with the session. */
    private val descriptors = LinkedHashMap<TerminalSession, SessionDescriptor>()

    val currentSession: TerminalSession?
        get() = _sessions.value.getOrNull(_currentIndex.value)

    val currentDescriptor: SessionDescriptor?
        get() = currentSession?.let { descriptors[it] }

    fun descriptorFor(session: TerminalSession?): SessionDescriptor? =
        session?.let { descriptors[it] }

    /** Descriptors in the same order as [sessions], for persisting and restoring. */
    fun orderedDescriptors(): List<SessionDescriptor> =
        _sessions.value.mapNotNull { descriptors[it] }

    fun addSession(session: TerminalSession, descriptor: SessionDescriptor? = null) {
        _sessions.value = _sessions.value + session
        if (descriptor != null) descriptors[session] = descriptor
        _currentIndex.value = _sessions.value.size - 1
        persist()
    }

    fun removeSession(index: Int) {
        if (index !in _sessions.value.indices) return
        val list = _sessions.value.toMutableList()
        list[index].finishIfRunning()
        list.removeAt(index)
        descriptors.remove(list.getOrNull(index))
        _sessions.value = list
        if (_currentIndex.value >= list.size) {
            _currentIndex.value = list.size - 1
        }
        if (list.isEmpty()) {
            stopService()
        }
        persist()
    }

    fun switchToSession(index: Int) {
        if (index in _sessions.value.indices) {
            _currentIndex.value = index
            persist()
        }
    }

    /** Keeps a rename in step with the session list and the drawer. */
    fun relabel(session: TerminalSession, label: String) {
        val existing = descriptors[session] ?: return
        descriptors[session] = when (existing) {
            is SessionDescriptor.Local -> existing.copy(label = label)
            is SessionDescriptor.Ssh -> existing.copy(label = label)
        }
        persist()
    }

    fun clearSessions() {
        _sessions.value.forEach { it.finishIfRunning() }
        _sessions.value = emptyList()
        _currentIndex.value = -1
        descriptors.clear()
        stopService()
        persist()
    }

    /**
     * Closes every session matching [predicate] and returns how many went.
     *
     * Used when a distro is uninstalled and when a saved server is deleted: in
     * both cases leaving the sessions running would keep a shell pointing at
     * something that is no longer there, and for SSH it would keep a connection
     * open to a host the user just asked to forget.
     */
    fun removeSessions(predicate: (SessionDescriptor) -> Boolean): Int {
        val doomed = _sessions.value.filter { s -> descriptors[s]?.let(predicate) == true }
        if (doomed.isEmpty()) return 0
        for (s in doomed) {
            s.finishIfRunning()
            descriptors.remove(s)
        }
        _sessions.value = _sessions.value.filterNot { it in doomed }
        if (_currentIndex.value >= _sessions.value.size) {
            _currentIndex.value = _sessions.value.size - 1
        }
        if (_sessions.value.isEmpty()) stopService()
        persist()
        return doomed.size
    }

    fun removeSessionsForDistro(distroName: String): Int =
        removeSessions { it is SessionDescriptor.Local && it.distro.equals(distroName, true) }

    /**
     * Closes the sessions of a deleted server.
     *
     * Matched on the server id, falling back to the host, so a session opened
     * against a server entry that has since been renamed still gets closed.
     */
    fun removeSessionsForServer(serverId: String, host: String? = null): Int =
        removeSessions {
            it is SessionDescriptor.Ssh &&
                (it.serverId == serverId || (host != null && it.host.equals(host, true)))
        }

    private fun stopService() {
        getApplication<Application>().stopService(
            Intent(getApplication(), TerminalService::class.java)
        )
    }

    /**
     * Writes the open sessions out so they survive the process being reclaimed.
     *
     * No descriptors means no sessions to restore, so the record is cleared
     * outright rather than left behind: an empty list restored on next launch
     * would be harmless, but a stale one surviving a normal exit would resurrect
     * sessions the user deliberately closed.
     */
    private fun persist() {
        val app = getApplication<Application>()
        val ordered = orderedDescriptors()
        if (ordered.isEmpty()) {
            SessionStore.clear(app)
            return
        }
        SessionStore.save(app, ordered, currentDescriptor?.id)
    }
}