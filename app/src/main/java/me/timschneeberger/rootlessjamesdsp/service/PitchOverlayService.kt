package me.timschneeberger.rootlessjamesdsp.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.getSystemService
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.view.PitchCarouselView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class PitchOverlayService : Service() {

    /** One adjustable parameter: its preference key, carousel and tab. */
    private class Param(
        val key: String,
        val carousel: PitchCarouselView,
        val tab: View,
        val tabName: TextView,
        val tabValue: TextView,
    )

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var windowParams: WindowManager.LayoutParams
    private lateinit var themedContext: Context

    // Order as shown: cents, semitones, octaves
    private lateinit var cents: Param
    private lateinit var semitones: Param
    private lateinit var octaves: Param
    private val all get() = listOf(cents, semitones, octaves)
    private var active = 1

    private lateinit var tvTotal: TextView
    private lateinit var tvPill: TextView
    private lateinit var btnPrev: ImageButton
    private lateinit var btnNext: ImageButton

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences(Constants.PREF_PITCHSHIFT, Context.MODE_MULTI_PROCESS) }
    // Overlay-only UI state (last open tab); kept apart from the DSP preferences
    private val uiPrefs by lazy { getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE) }

    // Keeps the carousels in sync when the values change elsewhere (app settings, "reset all").
    // Must be a field: SharedPreferences only keeps a weak reference to listeners.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        all.firstOrNull { it.key == key }?.let { it.carousel.setValue(read(it.key), animate = true) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService()!!

        windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 100
        }

        themedContext = ContextThemeWrapper(this, R.style.Theme_RootlessJamesDSP)
        overlayView = LayoutInflater.from(themedContext).inflate(R.layout.overlay_pitch_shift, null)

        setupViews()
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        windowManager.addView(overlayView, windowParams)
    }

    private fun setupViews() {
        val expandedView = overlayView.findViewById<View>(R.id.expanded_view)
        val collapsedView = overlayView.findViewById<View>(R.id.collapsed_view)
        tvTotal = overlayView.findViewById(R.id.tv_total)
        tvPill = overlayView.findViewById(R.id.tv_pill)
        btnPrev = overlayView.findViewById(R.id.btn_prev)
        btnNext = overlayView.findViewById(R.id.btn_next)

        overlayView.findViewById<ImageButton>(R.id.btn_collapse).setOnClickListener {
            expandedView.visibility = View.GONE
            collapsedView.visibility = View.VISIBLE
        }
        overlayView.findViewById<ImageButton>(R.id.btn_close).setOnClickListener { stopSelf() }
        overlayView.findViewById<ImageButton>(R.id.btn_reset_all).setOnClickListener { resetAll() }
        overlayView.findViewById<ImageButton>(R.id.btn_open_app).setOnClickListener { openApp() }

        setupDrag(overlayView.findViewById(R.id.drag_handle))
        setupCollapsedPill(collapsedView, expandedView)

        cents = setupParam(getString(R.string.key_pitchshift_cents), -100, 100,
            R.id.carousel_cents, R.id.tab_cents, R.id.tab_cents_name, R.id.tab_cents_value)
        semitones = setupParam(getString(R.string.key_pitchshift_semitones), -12, 12,
            R.id.carousel_semitones, R.id.tab_semitones, R.id.tab_semitones_name, R.id.tab_semitones_value)
        octaves = setupParam(getString(R.string.key_pitchshift_octaves), -3, 3,
            R.id.carousel_octaves, R.id.tab_octaves, R.id.tab_octaves_name, R.id.tab_octaves_value)

        all.forEachIndexed { index, param -> param.tab.setOnClickListener { select(index) } }
        setupStepButton(btnPrev, -1)
        setupStepButton(btnNext, 1)

        select(uiPrefs.getInt(KEY_ACTIVE_TAB, 1).coerceIn(0, 2))
        updateLabels()
    }

    private fun setupParam(key: String, min: Int, max: Int, carouselId: Int, tabId: Int, nameId: Int, valueId: Int): Param {
        val carousel = overlayView.findViewById<PitchCarouselView>(carouselId).apply {
            setup(min, max, read(key)) { signed(it) }
            // Apply only once the carousel has come to rest, so the pitch doesn't wobble while scrolling
            onValueSettled = { value -> save(key, value) }
            onCenterChanged = { updateLabels() }
        }
        return Param(key, carousel, overlayView.findViewById(tabId), overlayView.findViewById(nameId), overlayView.findViewById(valueId))
    }

    /** Shows the carousel of parameter [index]; the others stay hidden and can't be touched. */
    private fun select(index: Int) {
        active = index
        all.forEachIndexed { i, param ->
            param.carousel.visibility = if (i == index) View.VISIBLE else View.INVISIBLE
            styleTab(param, selected = i == index)
        }
        uiPrefs.edit().putInt(KEY_ACTIVE_TAB, index).apply()
        updateArrows()
    }

    /** ‹ › step the active carousel by one; holding a button keeps stepping. */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupStepButton(button: ImageButton, delta: Int) {
        val repeat = object : Runnable {
            override fun run() {
                val carousel = all[active].carousel
                if (!carousel.canStep(delta)) return
                carousel.stepBy(delta)
                handler.postDelayed(this, REPEAT_INTERVAL_MS)
            }
        }
        button.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.drawableHotspotChanged(event.x, event.y)
                    view.isPressed = true
                    all[active].carousel.stepBy(delta)
                    handler.postDelayed(repeat, REPEAT_DELAY_MS)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    handler.removeCallbacks(repeat)
                    true
                }
                else -> true
            }
        }
        // For accessibility services, which click instead of touching
        button.setOnClickListener { all[active].carousel.stepBy(delta) }
    }

    private fun updateArrows() {
        val carousel = all[active].carousel
        btnPrev.alpha = if (carousel.canStep(-1)) 1f else 0.3f
        btnNext.alpha = if (carousel.canStep(1)) 1f else 0.3f
    }

    private fun styleTab(param: Param, selected: Boolean) {
        val radius = 12 * resources.displayMetrics.density
        val shape = GradientDrawable().apply {
            cornerRadius = radius
            if (selected) {
                setColor(color(com.google.android.material.R.attr.colorSecondaryContainer, 0xFFE8DEF8.toInt()))
            } else {
                setColor(Color.TRANSPARENT)
                setStroke(resources.displayMetrics.density.roundToInt().coerceAtLeast(1),
                    color(com.google.android.material.R.attr.colorOutline, 0xFF79747E.toInt()))
            }
        }
        val mask = GradientDrawable().apply { cornerRadius = radius; setColor(Color.WHITE) }
        val ripple = ColorUtils.setAlphaComponent(color(com.google.android.material.R.attr.colorOnSurface, Color.BLACK), 0x1F)
        param.tab.background = RippleDrawable(ColorStateList.valueOf(ripple), shape, mask)
        param.tab.isSelected = selected

        val onSelected = color(com.google.android.material.R.attr.colorOnSecondaryContainer, 0xFF1D192B.toInt())
        param.tabName.setTextColor(if (selected) onSelected
            else color(com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF49454F.toInt()))
        param.tabValue.setTextColor(if (selected) onSelected
            else color(com.google.android.material.R.attr.colorOnSurface, 0xFF1D1B20.toInt()))
    }

    private fun color(attr: Int, fallback: Int) = MaterialColors.getColor(themedContext, attr, fallback)

    private fun read(key: String) = prefs.getFloat(key, 0f).roundToInt()

    private fun save(key: String, value: Int) {
        prefs.edit().putFloat(key, value.toFloat()).apply()
        sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
    }

    /** Brings JamesDSP's main screen to the front (the overlay stays open). Starting an activity
     *  from this service is allowed because the app holds the "display over other apps" permission. */
    private fun openApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching { startActivity(intent) }
    }

    private fun resetAll() {
        prefs.edit().apply { all.forEach { putFloat(it.key, 0f) } }.apply()
        sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
        // The preference listener scrolls the carousels back to 0
    }

    /** Header, pill and tabs follow the carousels live while scrolling. */
    private fun updateLabels() {
        if (!::octaves.isInitialized) return
        all.forEach { it.tabValue.text = signed(it.carousel.centerValue) }
        val total = octaves.carousel.centerValue * 12 + semitones.carousel.centerValue + cents.carousel.centerValue / 100.0
        val text = getString(R.string.pitch_overlay_total_format, formatSemitones(total))
        tvTotal.text = text
        tvPill.text = text
        updateArrows()
    }

    private fun formatSemitones(value: Double): String {
        val rounded = (value * 100).roundToInt()
        if (rounded == 0) return "0"
        val sign = if (rounded > 0) "+" else "−"
        val absolute = abs(rounded)
        val number = if (absolute % 100 == 0) (absolute / 100).toString()
        else String.format(Locale.US, "%.2f", absolute / 100.0).trimEnd('0')
        return sign + number
    }

    private fun signed(value: Int) = when {
        value > 0 -> "+$value"
        value < 0 -> "−${-value}"
        else -> "0"
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag(handle: View) {
        var startRawX = 0f; var startRawY = 0f
        var startWinX = 0; var startWinY = 0
        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX; startRawY = event.rawY
                    startWinX = windowParams.x; startWinY = windowParams.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    windowParams.x = startWinX + (event.rawX - startRawX).roundToInt()
                    windowParams.y = startWinY + (event.rawY - startRawY).roundToInt()
                    windowManager.updateViewLayout(overlayView, windowParams)
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupCollapsedPill(collapsedView: View, expandedView: View) {
        var startRawX = 0f; var startRawY = 0f
        var startWinX = 0; var startWinY = 0
        val tapThreshSq = (8 * resources.displayMetrics.density).let { it * it }

        collapsedView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX; startRawY = event.rawY
                    startWinX = windowParams.x; startWinY = windowParams.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX; val dy = event.rawY - startRawY
                    if (dx * dx + dy * dy > tapThreshSq) {
                        windowParams.x = startWinX + dx.roundToInt()
                        windowParams.y = startWinY + dy.roundToInt()
                        windowManager.updateViewLayout(overlayView, windowParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - startRawX; val dy = event.rawY - startRawY
                    if (dx * dx + dy * dy <= tapThreshSq) {
                        collapsedView.visibility = View.GONE
                        expandedView.visibility = View.VISIBLE
                    }
                    true
                }
                else -> false
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        runCatching { windowManager.removeView(overlayView) }
    }

    companion object {
        @Volatile var isRunning = false

        private const val UI_PREFS = "pitch_overlay_ui"
        private const val KEY_ACTIVE_TAB = "active_tab"
        private const val REPEAT_DELAY_MS = 400L
        private const val REPEAT_INTERVAL_MS = 90L
    }
}
