package com.redtermapp.ui

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.redtermapp.distro.DistroInstaller

class QuickSettingsTile : TileService() {

    override fun onStartListening() {
        updateTile()
    }

    override fun onStopListening() {}

    override fun onTileAdded() {
        updateTile()
    }

    override fun onTileRemoved() {}

    override fun onClick() {
        if (!isLocked) {
            val prefs = getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            val last = prefs.getString("last_distro", null)
            val distros = DistroInstaller(this).getInstalledDistros()
            val distro = last ?: distros.firstOrNull()
            if (distro != null) {
                TerminalActivity.launch(this, distro)
            } else {
                Toast.makeText(this, "Install a distro first", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val sessions = TerminalViewModel.get(application).sessions.value
        tile.state = if (sessions.isNotEmpty()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (sessions.isNotEmpty()) "RedTerm (${sessions.size})" else "RedTerm"
        tile.updateTile()
    }
}
