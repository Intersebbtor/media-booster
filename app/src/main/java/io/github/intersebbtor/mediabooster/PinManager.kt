package io.github.intersebbtor.mediabooster

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification

/**
 * Stand-in players. For every pinned app without its own visible media
 * notification, post a proxy MediaSession + MediaStyle notification so the
 * system media player keeps showing it. Commands are forwarded to the real app.
 */
object PinManager {

    private const val CHANNEL = "pins"
    private const val KEEP_ALIVE_MS = 2 * 60 * 1000L
    /** ColorOS drops paused players after ~5 min, AOSP after ~10. Take over after that. */
    private const val SYSTEM_TIMEOUT_MS = 5 * 60 * 1000L + 15_000L

    private class Pin(val pkg: String, val session: MediaSession) {
        var notifId = 0
        var visible = false
        var postedAt = 0L
        var lastKey: String? = null
        /** Package the card is posted as: a slot shell, or null for our own. */
        var target: String? = null
    }

    fun slotPackage(index: Int) = "io.github.intersebbtor.mediabooster.slot${index + 1}"

    fun slotsInstalled(c: Context): Int = (0 until Prefs.VISIBLE).count { i ->
        runCatching { c.packageManager.getPackageInfo(slotPackage(i), 0) }.isSuccess
    }

    /** Slot shells that are installed but have not granted delegation yet. */
    fun slotsNeedingActivation(c: Context, count: Int): List<String> {
        val nm = c.getSystemService(NotificationManager::class.java)
        return (0 until count).map { slotPackage(it) }.filter { pkg ->
            runCatching { c.packageManager.getPackageInfo(pkg, 0) }.isSuccess &&
                !runCatching { nm.canNotifyAsPackage(pkg) }.getOrDefault(false)
        }
    }

    private fun targetFor(c: Context, index: Int): String? {
        val slot = slotPackage(index)
        val nm = c.getSystemService(NotificationManager::class.java)
        return slot.takeIf { runCatching { nm.canNotifyAsPackage(it) }.getOrDefault(false) }
    }

    private fun post(nm: NotificationManager, target: String?, id: Int, n: Notification) {
        if (target != null) nm.notifyAsPackage(target, null, id, n) else nm.notify(id, n)
    }

    private fun cancel(nm: NotificationManager, target: String?, id: Int) {
        runCatching { if (target != null) nm.cancelAsPackage(target, null, id) else nm.cancel(id) }
    }

    private val pins = LinkedHashMap<String, Pin>()
    /** Last time each real app was seen playing. */
    private val lastPlaying = HashMap<String, Long>()
    private var takeOver: Runnable? = null
    private var cleaned = false

    /** Cards outlive our process. Clear leftovers once per process start. */
    private fun cleanupOnce(c: Context) {
        if (cleaned) return
        cleaned = true
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.cancelAll()
        for (i in 0 until Prefs.SLOTS) {
            val slot = slotPackage(i)
            if (!runCatching { nm.canNotifyAsPackage(slot) }.getOrDefault(false)) continue
            for (id in 1000 until 1000 + Prefs.SLOTS * 2) cancel(nm, slot, id)
        }
    }
    private val handler = Handler(Looper.getMainLooper())
    private var appCtx: Context? = null
    private var keepAlive: Runnable? = null

    enum class Status { LIVE, PINNED, WAITING }

    data class Place(val pkg: String, val status: Status)

    /** For the UI: the panel places in order, and what waits in the background. */
    @Volatile
    var places: List<Place> = emptyList()
        private set
    @Volatile
    var background: List<String> = emptyList()
        private set

    /** For the UI: what each app currently is in the panel. */
    @Volatile
    var status: Map<String, Status> = emptyMap()
        private set

    /** Something changed while the phone was locked. Applied on unlock. */
    private var pendingWhileLocked = false

    /** Locked or screen off: any card change would light up the AOD and move the lock screen clock. */
    private fun locked(c: Context): Boolean {
        val interactive = c.getSystemService(android.os.PowerManager::class.java).isInteractive
        val keyguard = c.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
        return !interactive || keyguard
    }

    /** Recompute all stand-ins. Call from the listener service (needs session access). */
    fun refresh(ctx: Context, active: Array<StatusBarNotification>?) {
        val c = ctx.applicationContext
        appCtx = c
        if (locked(c)) {
            pendingWhileLocked = true
            return
        }
        pendingWhileLocked = false
        ensureChannel(c)
        cleanupOnce(c)
        val sessions = MediaSessions.controllers(c) ?: emptyMap()
        val remembered = Prefs.remembered(c)
        val now = System.currentTimeMillis()

        // Latest media notification post time per real app. The system restarts its
        // paused-player timeout on every post, so this tells how long the real card stays.
        val realPosted = HashMap<String, Long>()
        active.orEmpty()
            .filter { !it.packageName.startsWith(c.packageName) && it.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) }
            .forEach { realPosted[it.packageName] = maxOf(realPosted[it.packageName] ?: 0L, it.postTime) }

        // Feed the history: playing now, or a fresh player card
        for ((pkg, ctrl) in sessions) {
            if (MediaSessions.isPlaying(ctrl)) {
                lastPlaying[pkg] = now
                if (pkg in remembered) Prefs.touch(c, pkg, now)
            }
        }
        for ((pkg, t) in realPosted) if (pkg in remembered) Prefs.touch(c, pkg, t)
        // Allowed but never seen playing (fresh install, just ticked): queue at the end
        val known = Prefs.history(c).map { it.pkg }.toSet()
        remembered.filter { it !in known }.forEachIndexed { i, pkg -> Prefs.touch(c, pkg, 1L + i) }

        // Real cards currently on screen, from any app (browsers too)
        val realShown = mutableSetOf<String>()
        var nextTimeout = Long.MAX_VALUE
        for ((pkg, posted) in realPosted) {
            val playing = sessions[pkg]?.let { MediaSessions.isPlaying(it) } == true
            val since = minOf(now - posted, now - (lastPlaying[pkg] ?: 0L))
            if (playing || since < SYSTEM_TIMEOUT_MS) {
                realShown += pkg
                if (!playing) nextTimeout = minOf(nextTimeout, SYSTEM_TIMEOUT_MS - since)
            }
        }
        if (nextTimeout != Long.MAX_VALUE) scheduleTakeOver(nextTimeout)

        // Fill the free places with the most recently used remembered apps
        val free = (Prefs.VISIBLE - realShown.size).coerceAtLeast(0)
        val visible = Prefs.history(c).map { it.pkg }
            .filter { it in remembered && it !in realShown && MediaApps.label(c, it) != null }
            .take(free)

        pins.keys.filter { it !in visible }.forEach { release(c, it) }
        // The panel puts the most recently posted card first: post the last place first
        for (index in visible.indices.reversed()) {
            show(c, visible[index], index, sessions[visible[index]])
        }

        val realOrdered = realShown.sortedByDescending { realPosted[it] ?: 0L }
        places = (realOrdered.map { Place(it, Status.LIVE) } + visible.map { Place(it, Status.PINNED) }).take(Prefs.VISIBLE)
        val placed = places.map { it.pkg }.toSet()
        background = Prefs.history(c).map { it.pkg }.filter { it in remembered && it !in placed }
        status = buildMap {
            Prefs.history(c).forEach { put(it.pkg, Status.WAITING) }
            visible.forEach { put(it, Status.PINNED) }
            realShown.forEach { put(it, Status.LIVE) }
        }
        scheduleKeepAlive()
    }

    fun releaseAll(ctx: Context) {
        pins.keys.toList().forEach { release(ctx.applicationContext, it) }
        keepAlive?.let { handler.removeCallbacks(it) }
    }

    // ---------- commands (from session callback or notification action) ----------

    fun command(ctx: Context, pkg: String, action: String) {
        val c = ctx.applicationContext
        val real = MediaSessions.controllerFor(c, pkg)
        when (action) {
            PinActionReceiver.PLAY_PAUSE -> when {
                real != null && MediaSessions.isPlaying(real) -> real.transportControls.pause()
                real != null -> real.transportControls.play()
                else -> Resumer.resume(c, pkg) {}
            }
            PinActionReceiver.PLAY -> if (real != null) real.transportControls.play() else Resumer.resume(c, pkg) {}
            PinActionReceiver.PAUSE -> real?.transportControls?.pause()
            PinActionReceiver.NEXT -> real?.let { seek(it, forward = true) }
            PinActionReceiver.PREV -> real?.let { seek(it, forward = false) }
        }
    }

    private fun seek(c: MediaController, forward: Boolean) {
        val tc = c.transportControls
        val actions = c.playbackState?.actions ?: 0L
        val skip = if (forward) PlaybackState.ACTION_SKIP_TO_NEXT else PlaybackState.ACTION_SKIP_TO_PREVIOUS
        val step = if (forward) PlaybackState.ACTION_FAST_FORWARD else PlaybackState.ACTION_REWIND
        val custom = MediaSessions.customSeek(c, forward)
        when {
            actions and skip != 0L -> if (forward) tc.skipToNext() else tc.skipToPrevious()
            actions and step != 0L -> if (forward) tc.fastForward() else tc.rewind()
            custom != null -> tc.sendCustomAction(custom, custom.extras)
        }
    }

    // ---------- internals ----------

    private fun pin(c: Context, pkg: String, index: Int): Pin = pins.getOrPut(pkg) {
        val s = MediaSession(c, "pin_$pkg")
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = command(c, pkg, PinActionReceiver.PLAY)
            override fun onPause() = command(c, pkg, PinActionReceiver.PAUSE)
            override fun onSkipToNext() = command(c, pkg, PinActionReceiver.NEXT)
            override fun onSkipToPrevious() = command(c, pkg, PinActionReceiver.PREV)
        }, handler)
        s.isActive = true
        // Stable id per pin. Two ids per pin so keep-alive can swap without a gap.
        Pin(pkg, s).also { it.notifId = 1000 + index * 2 }
    }

    private fun show(c: Context, pkg: String, index: Int, real: MediaController?) {
        val p = pin(c, pkg, index)
        val label = MediaApps.label(c, pkg) ?: pkg

        val md = real?.metadata
        var title = MediaSessions.title(md)
        var artist = MediaSessions.subtitle(md)
        var art: Bitmap? = MediaSessions.art(md)?.let { Prefs.scale(it, 320) }
        if (title != null) {
            Prefs.saveLast(c, pkg, title, artist, art?.let { Prefs.scale(it) })
        } else {
            val last = Prefs.last(c, pkg)
            title = last?.title
            artist = last?.subtitle
            art = Prefs.lastArt(c, pkg)
        }
        if (art == null) art = MediaApps.iconBitmap(c, pkg, 192)

        val shownTitle = title ?: label
        val shownSub = if (artist != null) "$label · $artist" else label

        p.session.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, shownTitle)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, shownSub)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, shownTitle)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, shownSub)
            .apply { if (art != null) putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art) }
            .build())

        val playing = real != null && MediaSessions.isPlaying(real)
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE
        val canPrev = MediaSessions.canSeek(real, forward = false)
        val canNext = MediaSessions.canSeek(real, forward = true)
        if (canPrev) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (canNext) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        val pos = real?.playbackState?.position ?: 0L
        p.session.setPlaybackState(PlaybackState.Builder()
            .setActions(actions)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, pos, if (playing) 1f else 0f)
            .build())

        val open = launch(c, pkg)
        if (open != null) p.session.setSessionActivity(open)

        // Repost only when content changed, or keep-alive is due
        val target = targetFor(c, index)
        // Bitmaps are reloaded on every refresh, so compare by content, not identity
        val artKey = art?.let { "${it.width}x${it.height}:${it.getPixel(it.width / 2, it.height / 2)}:${it.getPixel(it.width / 4, it.height / 4)}" }
        val key = "$shownTitle|$shownSub|$playing|$canPrev|$canNext|$artKey|$target|$index"
        val due = System.currentTimeMillis() - p.postedAt > KEEP_ALIVE_MS
        if (p.visible && key == p.lastKey && !due) return
        p.lastKey = key

        val nm = c.getSystemService(NotificationManager::class.java)
        // Reordered: move card to the id range of its new position
        if ((p.notifId - 1000) / 2 != index) {
            if (p.visible) cancel(nm, p.target, p.notifId)
            p.visible = false
            p.notifId = 1000 + index * 2
        }
        if (p.visible && target != p.target) {
            cancel(nm, p.target, p.notifId)
            p.visible = false
        }
        // Same id on keep-alive: an update already restarts the system's paused-player
        // timeout. A new id would count as a new notification and light up the AOD.
        val oldId = p.notifId
        val newId = oldId

        val b = Notification.Builder(c, CHANNEL)
            .setSmallIcon(Icon.createWithResource(c, R.drawable.ic_booster))
            .setContentTitle(shownTitle)
            .setContentText(shownSub)
            .setLargeIcon(art)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setSortKey("pin_$index")
            .setCategory(Notification.CATEGORY_TRANSPORT)
            // Stand-ins belong to quick settings, not to the lock screen media widget
            .setVisibility(Notification.VISIBILITY_SECRET)
        if (open != null) b.setContentIntent(open)

        val compact = mutableListOf<Int>()
        var i = 0
        if (canPrev) {
            b.addAction(action(c, pkg, PinActionReceiver.PREV, R.drawable.ic_prev, c.getString(R.string.cd_prev))); i++
        }
        b.addAction(action(c, pkg, PinActionReceiver.PLAY_PAUSE,
            if (playing) R.drawable.ic_pause else R.drawable.ic_play, c.getString(R.string.cd_play)))
        compact += i; i++
        if (canNext) {
            b.addAction(action(c, pkg, PinActionReceiver.NEXT, R.drawable.ic_next, c.getString(R.string.cd_next)))
        }
        b.setStyle(Notification.MediaStyle()
            .setMediaSession(p.session.sessionToken)
            .setShowActionsInCompactView(*compact.toIntArray()))

        post(nm, target, newId, b.build())
        if (newId != oldId && p.visible) cancel(nm, target, oldId)
        p.target = target
        p.notifId = newId
        p.visible = true
        p.postedAt = System.currentTimeMillis()
    }

    private fun hide(c: Context, pkg: String) {
        val p = pins[pkg] ?: return
        if (p.visible) {
            cancel(c.getSystemService(NotificationManager::class.java), p.target, p.notifId)
            p.visible = false
            p.lastKey = null
        }
    }

    private fun release(c: Context, pkg: String) {
        hide(c, pkg)
        pins.remove(pkg)?.session?.release()
    }

    private fun scheduleTakeOver(delay: Long) {
        takeOver?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            val c = appCtx ?: return@Runnable
            MediaListenerService.instance?.refreshPins() ?: refresh(c, null)
        }
        takeOver = r
        handler.postDelayed(r, delay.coerceAtLeast(1000))
    }

    /** Unlocked: refresh right away, cards may have timed out while locked. */
    fun onUnlocked(ctx: Context) {
        pins.values.forEach { it.postedAt = 0L }
        MediaListenerService.instance?.refreshPins() ?: refresh(ctx, null)
    }

    fun onScreenOff() {
        keepAlive?.let { handler.removeCallbacks(it) }
    }

    private fun scheduleKeepAlive() {
        keepAlive?.let { handler.removeCallbacks(it) }
        if (pins.values.none { it.visible }) return
        // Nobody sees the panel while locked. Updating then only wakes the AOD.
        if (appCtx?.let { locked(it) } != false) return
        val r = Runnable {
            val c = appCtx ?: return@Runnable
            MediaListenerService.instance?.refreshPins() ?: refresh(c, null)
        }
        keepAlive = r
        handler.postDelayed(r, KEEP_ALIVE_MS + 1000)
    }

    private fun ensureChannel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, c.getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        })
    }

    private fun action(c: Context, pkg: String, action: String, icon: Int, title: String): Notification.Action {
        val i = Intent(c, PinActionReceiver::class.java)
            .setAction(action)
            .setData(Uri.parse("mediabooster://action/$pkg/$action"))
            .putExtra(PinActionReceiver.EXTRA_PKG, pkg)
        val pi = PendingIntent.getBroadcast(c, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Action.Builder(Icon.createWithResource(c, icon), title, pi).build()
    }

    private fun launch(c: Context, pkg: String): PendingIntent? {
        val i = c.packageManager.getLaunchIntentForPackage(pkg) ?: return null
        i.data = Uri.parse("mediabooster://open/$pkg")
        return PendingIntent.getActivity(c, pkg.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
