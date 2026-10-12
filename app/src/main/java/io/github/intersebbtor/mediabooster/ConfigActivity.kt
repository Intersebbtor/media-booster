package io.github.intersebbtor.mediabooster

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class ConfigActivity : Activity() {

    private var widgetId = Prefs.GLOBAL
    /** True when opened by the launcher while adding a widget. */
    private var addFlow = false
    private val slots = arrayOfNulls<String>(Prefs.SLOTS)
    private var rows = Prefs.DEFAULT_ROWS
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView

    private val known = listOf(
        "com.spotify.music",
        "com.audible.application",
        "au.com.shiftyjelly.pocketcasts",
        "com.google.android.apps.youtube.music",
        "com.amazon.mp3",
        "deezer.android.app",
        "com.aspiro.tidal",
        "com.apple.android.music",
        "de.danoeh.antennapod",
        "com.soundcloud.android",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        readIntent(intent)

        scroll = ScrollView(this).apply { isFillViewport = true }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(24))
        }
        scroll.addView(content)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            v.setPadding(b.left, b.top, b.right, maxOf(b.bottom, ime.bottom))
            insets
        }
        scroll.setBackgroundColor(getColor(R.color.widget_bg))
        setContentView(scroll)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
        render()
    }

    override fun onResume() {
        super.onResume()
        // Fresh panel state for the status labels
        MediaListenerService.instance?.refreshPins()
        render()
    }

    private fun readIntent(i: Intent) {
        widgetId = Prefs.GLOBAL
        addFlow = false
    }

    // ---------- UI ----------

    private fun render() {
        content.removeAllViews()
        content.addView(header())
        renderSetup()
        renderSlots()
    }

    private fun header(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(ImageView(this@ConfigActivity).apply {
            setImageDrawable(getDrawable(R.mipmap.ic_launcher))
            layoutParams = LinearLayout.LayoutParams(dp(60), dp(60)).apply { marginEnd = dp(16) }
        })
        addView(LinearLayout(this@ConfigActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(text(getString(R.string.app_name), 26f, bold = true))
            addView(text(getString(R.string.subtitle_edit), 14f, secondary = true, top = 2))
        })
    }

    private fun renderSetup() {
        val access = MediaSessions.hasAccess(this)
        val battery = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        val post = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (access && battery && post) {
            content.addView(statusLine(getString(R.string.all_set), ok = true))
            return
        }

        content.addView(sectionTitle(getString(R.string.sec_setup)))
        val card = card()

        card.addView(checkRow(
            getString(R.string.post_title),
            getString(if (post) R.string.post_ok else R.string.post_missing),
            post,
            getString(R.string.allow),
        ) { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1) })
        card.addView(divider())

        card.addView(checkRow(
            getString(R.string.notif_title),
            if (access) getString(R.string.notif_ok)
            else getString(R.string.notif_missing),
            access,
            getString(R.string.grant),
        ) { openListenerSettings() })

        if (!access) {
            card.addView(text(
                getString(R.string.restricted_hint),
                13f, secondary = true, top = 8,
            ))
            card.addView(linkButton(getString(R.string.open_app_info)) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            })
        }

        card.addView(divider())

        card.addView(checkRow(
            getString(R.string.battery_title),
            if (battery) getString(R.string.battery_ok)
            else getString(R.string.battery_missing),
            battery,
            getString(R.string.allow),
        ) { requestBatteryExemption() })

        content.addView(card)
    }

    private fun renderSlots() {
        val remembered = Prefs.remembered(this)
        val history = Prefs.history(this)
        val places = PinManager.places
        val waiting = PinManager.background.ifEmpty {
            history.map { it.pkg }.filter { it in remembered && places.none { p -> p.pkg == it } }
        }

        // Quick settings: the 3 places of the system media panel
        content.addView(sectionTitle(getString(R.string.sec_qs)))
        val qs = card()
        for (i in 0 until Prefs.VISIBLE) {
            if (i > 0) qs.addView(divider())
            qs.addView(placeRow(i + 1, places.getOrNull(i)))
        }
        content.addView(qs)
        content.addView(text(getString(R.string.hist_hint), 13f, secondary = true, top = 8))

        // Background: remembered, waiting for a free place
        content.addView(sectionTitle(getString(R.string.sec_bg)))
        val bg = card()
        if (waiting.isEmpty()) bg.addView(text(getString(R.string.bg_empty), 14f, secondary = true).apply {
            setPadding(0, dp(8), 0, dp(8))
        })
        waiting.forEachIndexed { i, pkg ->
            if (i > 0) bg.addView(divider())
            bg.addView(appRow(pkg, R.drawable.ic_clock, getString(R.string.st_waiting), accent = false,
                trailing = imageButton(R.drawable.ic_close, getString(R.string.forget_cd)) {
                    Prefs.forget(this, pkg)
                    applyChange()
                }))
        }
        content.addView(bg)

        // Which apps may be remembered
        content.addView(sectionTitle(getString(R.string.sec_remember)))
        val search = android.widget.EditText(this).apply {
            hint = getString(R.string.search_hint)
            setText(query)
            setSingleLine()
            textSize = 16f
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_secondary))
            background = rounded(R.color.slot_bg, 50)
            setPadding(dp(16), dp(12), dp(18), dp(12))
            val icon = getDrawable(R.drawable.ic_search)!!.mutate().apply { setTint(getColor(R.color.text_secondary)) }
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
            compoundDrawablePadding = dp(10)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(10) }
        }
        content.addView(search)
        // Keyboard covers the results otherwise: move the field to the top
        search.setOnFocusChangeListener { v, focused ->
            if (focused) v.postDelayed({ scroll.smoothScrollTo(0, v.top - dp(12)) }, 250)
        }
        search.setOnClickListener { v -> v.postDelayed({ scroll.smoothScrollTo(0, v.top - dp(12)) }, 250) }
        val list = card()
        content.addView(list)
        fillAppList(list, remembered, history.map { it.pkg })
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) {
                query = e?.toString().orEmpty()
                fillAppList(list, Prefs.remembered(this@ConfigActivity), Prefs.history(this@ConfigActivity).map { it.pkg })
            }
        })
        content.addView(text(getString(R.string.remember_hint), 13f, secondary = true, top = 8))
    }

    private fun placeRow(n: Int, place: PinManager.Place?): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(60)
        setPadding(0, dp(6), 0, dp(6))
        addView(text(n.toString(), 14f, bold = true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(getColor(if (place != null) R.color.play_bg else R.color.slot_active_bg))
            }
            setTextColor(getColor(if (place != null) R.color.play_icon else R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(12) }
        })
        if (place == null) {
            addView(View(this@ConfigActivity).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(2), getColor(R.color.text_secondary), dp(4).toFloat(), dp(4).toFloat())
                    alpha = 90
                }
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) }
            })
            addView(text(getString(R.string.place_free), 15f, secondary = true))
            return@apply
        }
        val live = place.status == PinManager.Status.LIVE
        val inner = appRow(place.pkg,
            if (live) R.drawable.ic_live else R.drawable.ic_pin,
            getString(if (live) R.string.st_live else R.string.st_pinned),
            accent = true,
            trailing = if (live) null else imageButton(R.drawable.ic_close, getString(R.string.forget_cd)) {
                Prefs.forget(this@ConfigActivity, place.pkg)
                applyChange()
            })
        inner.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        addView(inner)
    }

    /** App icon, name and a status line with a small icon. */
    private fun appRow(pkg: String, statusIcon: Int, status: String, accent: Boolean, trailing: View?): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            addView(ImageView(this@ConfigActivity).apply {
                setImageDrawable(MediaApps.icon(this@ConfigActivity, pkg))
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) }
            })
            addView(LinearLayout(this@ConfigActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(text(MediaApps.label(this@ConfigActivity, pkg) ?: pkg, 16f, bold = true).apply {
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                })
                addView(LinearLayout(this@ConfigActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(2), 0, 0)
                    val color = getColor(if (accent) R.color.play_bg else R.color.text_secondary)
                    addView(ImageView(this@ConfigActivity).apply {
                        setImageResource(statusIcon)
                        setColorFilter(color)
                        layoutParams = LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginEnd = dp(6) }
                    })
                    addView(text(status, 13f).apply { setTextColor(color); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                })
            })
            if (trailing != null) addView(trailing)
        }

    private fun imageButton(icon: Int, cd: String, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(icon)
        setColorFilter(getColor(R.color.text_secondary))
        contentDescription = cd
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = ripple(null, 24)
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        setOnClickListener { onClick() }
    }

    private var query = ""
    private var allApps: List<AppEntry>? = null

    /** Media apps by default. While searching, all launchable apps (e.g. browsers). */
    private fun fillAppList(list: LinearLayout, remembered: Set<String>, history: List<String>) {
        list.removeAllViews()
        val q = query.trim().lowercase()
        val base = if (q.isEmpty()) {
            MediaApps.mediaApps(this) +
                (remembered + history).mapNotNull { p -> MediaApps.label(this, p)?.let { AppEntry(p, it) } }
        } else {
            (allApps ?: MediaApps.allApps(this).also { allApps = it }).filter { q in it.label.lowercase() }
        }
        val apps = base.distinctBy { it.pkg }
            .sortedWith(compareByDescending<AppEntry> { it.pkg in remembered }.thenBy { it.label.lowercase() })
        if (apps.isEmpty()) list.addView(text(getString(R.string.search_empty), 14f, secondary = true))
        apps.forEachIndexed { i, app ->
            if (i > 0) list.addView(divider())
            val sw = android.widget.Switch(this).apply {
                isChecked = app.pkg in remembered
                isClickable = false
            }
            list.addView(row(
                icon = MediaApps.icon(this, app.pkg),
                title = app.label,
                subtitle = null,
                trailing = sw,
            ) {
                val on = !sw.isChecked
                sw.isChecked = on
                Prefs.setRemembered(this, app.pkg, on)
                MediaListenerService.instance?.refreshPins()
            })
        }
    }

    private fun applyChange() {
        MediaListenerService.instance?.refreshPins()
        content.postDelayed({ render() }, 400)
    }

    private fun openListenerSettings() {
        val cn = MediaSessions.listenerComponent(this)
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, cn.flattenToString())
        runCatching { startActivity(detail) }.onFailure {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    private fun requestBatteryExemption() {
        val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        runCatching { startActivity(i) }.onFailure {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    // ---------- view helpers ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(
        s: String, size: Float, bold: Boolean = false, secondary: Boolean = false,
        accent: Boolean = false, top: Int = 0,
    ) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(getColor(when {
            accent -> R.color.play_bg
            secondary -> R.color.text_secondary
            else -> R.color.text_primary
        }))
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(top) }
    }

    private fun sectionTitle(s: String) = text(s.uppercase(), 13f, bold = true, secondary = true, top = 28).apply {
        letterSpacing = 0.08f
        setPadding(dp(4), 0, 0, dp(8))
    }

    private fun rounded(colorRes: Int, radius: Int) = GradientDrawable().apply {
        setColor(getColor(colorRes))
        cornerRadius = dp(radius).toFloat()
    }

    private fun ripple(content: Drawable?, radius: Int) = RippleDrawable(
        ColorStateList.valueOf(0x22888888),
        content,
        GradientDrawable().apply { setColor(0xFF000000.toInt()); cornerRadius = dp(radius).toFloat() },
    )

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(R.color.slot_bg, 20)
        setPadding(dp(16), dp(12), dp(16), dp(14))
        clipToOutline = true
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(getColor(R.color.text_secondary))
        alpha = 0.15f
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            .apply { topMargin = dp(8); bottomMargin = dp(8) }
    }

    private fun statusLine(s: String, ok: Boolean) = text(s, 14f, bold = true, accent = ok, top = 16).apply {
        background = rounded(R.color.slot_active_bg, 50)
        setPadding(dp(14), dp(8), dp(14), dp(8))
    }

    private fun row(
        icon: Drawable?, title: String, subtitle: String?, trailing: View?,
        padH: Int = 0, onClick: (() -> Unit)?,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(56)
        setPadding(dp(padH), dp(6), dp(padH), dp(6))
        if (onClick != null) {
            background = ripple(null, 12)
            setOnClickListener { onClick() }
        }
        addView(ImageView(this@ConfigActivity).apply {
            if (icon != null) setImageDrawable(icon)
            else {
                background = rounded(R.color.slot_active_bg, 10)
            }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) }
            visibility = if (icon == null && onClick == null && padH > 0) View.INVISIBLE else View.VISIBLE
        })
        addView(LinearLayout(this@ConfigActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(text(title, 16f, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            if (subtitle != null) addView(text(subtitle, 13f, secondary = true, top = 2).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
        })
        if (trailing != null) addView(trailing)
    }

    private fun checkRow(title: String, desc: String, ok: Boolean, action: String, onAction: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            addView(text(if (ok) "✓" else "!", 16f, bold = true).apply {
                gravity = Gravity.CENTER
                setTextColor(getColor(if (ok) R.color.play_icon else R.color.text_primary))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(getColor(if (ok) R.color.play_bg else R.color.slot_active_bg))
                }
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(14) }
            })
            addView(LinearLayout(this@ConfigActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(text(title, 16f, bold = true))
                addView(text(desc, 13f, secondary = true, top = 2))
            })
            if (!ok) addView(pillButton(action, onAction).apply {
                (layoutParams as LinearLayout.LayoutParams).marginStart = dp(10)
            })
        }

    private fun pillButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 14f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(getColor(R.color.play_icon))
        gravity = Gravity.CENTER
        minHeight = dp(40)
        setPadding(dp(16), 0, dp(16), 0)
        background = ripple(rounded(R.color.play_bg, 50), 50)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnClickListener { onClick() }
    }

    private fun linkButton(label: String, onClick: () -> Unit) = text(label, 14f, bold = true, accent = true, top = 4).apply {
        minHeight = dp(40)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(4), dp(8), dp(4))
        setOnClickListener { onClick() }
    }

    private fun primaryButton(label: String, onClick: () -> Unit) = pillButton(label, onClick).apply {
        textSize = 16f
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(24) }
    }

    private fun iconButton(label: String, enabled: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(getColor(R.color.text_primary))
        alpha = if (enabled) 1f else 0.25f
        isEnabled = enabled
        background = ripple(null, 24)
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        if (enabled) setOnClickListener { onClick() }
    }
}
