package io.github.intersebbtor.mediabooster

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

class PinActionReceiver : BroadcastReceiver() {

    companion object {
        const val PLAY_PAUSE = "io.github.intersebbtor.mediabooster.PLAY_PAUSE"
        const val PLAY = "io.github.intersebbtor.mediabooster.PLAY"
        const val PAUSE = "io.github.intersebbtor.mediabooster.PAUSE"
        const val NEXT = "io.github.intersebbtor.mediabooster.NEXT"
        const val PREV = "io.github.intersebbtor.mediabooster.PREV"
        const val EXTRA_PKG = "pkg"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.getStringExtra(EXTRA_PKG) ?: return
        val action = intent.action ?: return
        val pending = goAsync()
        PinManager.command(context, pkg, action)
        // Resumer may need a few seconds on the main thread
        Handler(Looper.getMainLooper()).postDelayed({ pending.finish() }, 6000)
    }
}
