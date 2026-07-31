package com.redtermapp.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TerminalViewModel(application: Application) : AndroidViewModel(application) {

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
}
