package com.redtermapp.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import com.redtermapp.service.TerminalService
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    val currentSession: TerminalSession?
        get() = _sessions.value.getOrNull(_currentIndex.value)

    fun addSession(session: TerminalSession) {
        _sessions.value = _sessions.value + session
        _currentIndex.value = _sessions.value.size - 1
    }

    fun removeSession(index: Int) {
        if (index !in _sessions.value.indices) return
        val list = _sessions.value.toMutableList()
        list[index].finishIfRunning()
        list.removeAt(index)
        _sessions.value = list
        if (_currentIndex.value >= list.size) {
            _currentIndex.value = list.size - 1
        }
        if (list.isEmpty()) {
            getApplication<Application>().stopService(
                Intent(getApplication(), TerminalService::class.java)
            )
        }
    }

    fun switchToSession(index: Int) {
        if (index in _sessions.value.indices) {
            _currentIndex.value = index
        }
    }

    fun clearSessions() {
        _sessions.value.forEach { it.finishIfRunning() }
        _sessions.value = emptyList()
        _currentIndex.value = -1
    }

    fun removeSessionsForDistro(distroName: String) {
        val list = _sessions.value.toMutableList()
        val kept = mutableListOf<TerminalSession>()
        for (s in list) {
            if (s.mSessionName.equals(distroName, ignoreCase = true)) {
                s.finishIfRunning()
            } else {
                kept.add(s)
            }
        }
        _sessions.value = kept
        if (_currentIndex.value >= kept.size) {
            _currentIndex.value = kept.size - 1
        }
        if (kept.isEmpty()) {
            getApplication<Application>().stopService(
                Intent(getApplication(), TerminalService::class.java)
            )
        }
    }
}
