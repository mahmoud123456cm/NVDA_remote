package com.example.nvdapocket

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: tap to connect with the last used profile, tap again to disconnect. */
class ConnectTile : TileService() {
    override fun onStartListening() { update() }

    private fun update() {
        val t = qsTile ?: return
        t.state = if (Remote.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "NVDA Pocket"
        t.updateTile()
    }

    override fun onClick() {
        if (Remote.active) { Remote.stop(this); update(); return }
        val i = Intent(this, MainActivity::class.java).putExtra("connect_last", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 5, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            startActivityAndCollapse(i)
        }
    }
}
