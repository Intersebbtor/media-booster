package io.github.intersebbtor.playerpin.slot

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Bundle

/** Grant Player Pin the right to post this slot's player card, then vanish. */
class ActivateActivity : Activity() {

    private val host = "io.github.intersebbtor.playerpin"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("pins", "Player", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        })
        nm.notificationDelegate = host
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        } else {
            finish()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        finish()
    }
}
