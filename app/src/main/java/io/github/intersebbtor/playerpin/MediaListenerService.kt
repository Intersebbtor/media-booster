package io.github.intersebbtor.playerpin

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Keeps the app alive, watches real sessions and media notifications, drives the pins. */
class MediaListenerService : NotificationListenerService() {

    companion object {
        @Volatile
        var instance: MediaListenerService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private val refresh = Runnable { refreshPins() }
    private val tracked = HashMap<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private var msm: MediaSessionManager? = null

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        track(list ?: emptyList())
    }

    fun refreshPins() {
        val active = runCatching { activeNotifications }.getOrNull()
        PinManager.refresh(this, active)
    }

    fun schedule() {
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, 300)
    }

    override fun onListenerConnected() {
        instance = this
        val m = getSystemService(MediaSessionManager::class.java)
        msm = m
        val cn = MediaSessions.listenerComponent(this)
        runCatching {
            m.addOnActiveSessionsChangedListener(sessionsListener, cn, handler)
            track(m.getActiveSessions(cn))
        }
        schedule()
    }

    override fun onListenerDisconnected() {
        instance = null
        runCatching { msm?.removeOnActiveSessionsChangedListener(sessionsListener) }
        untrackAll()
        requestRebind(MediaSessions.listenerComponent(this))
    }

    override fun onDestroy() {
        instance = null
        untrackAll()
        PinManager.releaseAll(this)
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != packageName && isMedia(sbn)) schedule()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName != packageName && isMedia(sbn)) schedule()
    }

    private fun isMedia(sbn: StatusBarNotification) =
        sbn.notification.extras.containsKey(android.app.Notification.EXTRA_MEDIA_SESSION)

    private fun track(list: List<MediaController>) {
        val own = packageName
        val others = list.filter { it.packageName != own }
        val tokens = others.map { it.sessionToken }.toSet()
        tracked.keys.filter { it !in tokens }.forEach { t ->
            tracked.remove(t)?.let { (c, cb) -> c.unregisterCallback(cb) }
        }
        for (c in others) {
            if (c.sessionToken in tracked) continue
            val cb = object : MediaController.Callback() {
                private var last = c.playbackState?.let { it.state to it.actions }
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    val now = state?.let { it.state to it.actions }
                    if (now != last) {
                        last = now
                        schedule()
                    }
                }
                override fun onMetadataChanged(metadata: MediaMetadata?) = schedule()
                override fun onSessionDestroyed() = schedule()
            }
            c.registerCallback(cb, handler)
            tracked[c.sessionToken] = c to cb
        }
        schedule()
    }

    private fun untrackAll() {
        tracked.values.forEach { (c, cb) -> runCatching { c.unregisterCallback(cb) } }
        tracked.clear()
    }
}
