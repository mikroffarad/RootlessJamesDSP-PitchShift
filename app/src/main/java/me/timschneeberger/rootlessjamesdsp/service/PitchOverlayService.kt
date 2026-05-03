package me.timschneeberger.rootlessjamesdsp.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.getSystemService
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import kotlin.math.roundToInt

class PitchOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var params: WindowManager.LayoutParams

    private val prefs get() = getSharedPreferences(Constants.PREF_PITCHSHIFT, Context.MODE_MULTI_PROCESS)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService()!!

        params = WindowManager.LayoutParams(
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

        val themedContext = ContextThemeWrapper(this, R.style.Theme_RootlessJamesDSP)
        overlayView = LayoutInflater.from(themedContext).inflate(R.layout.overlay_pitch_shift, null)

        setupViews()
        windowManager.addView(overlayView, params)
    }

    private fun setupViews() {
        val expandedView = overlayView.findViewById<View>(R.id.expanded_view)
        val collapsedView = overlayView.findViewById<View>(R.id.collapsed_view)

        overlayView.findViewById<ImageButton>(R.id.btn_collapse).setOnClickListener {
            expandedView.visibility = View.GONE
            collapsedView.visibility = View.VISIBLE
        }

        overlayView.findViewById<ImageButton>(R.id.btn_close).setOnClickListener {
            stopSelf()
        }

        setupDrag(overlayView.findViewById(R.id.drag_handle))
        setupCollapsedPill(collapsedView, expandedView)

        setupRow(
            seekBar = overlayView.findViewById(R.id.seekbar_octaves),
            tvValue = overlayView.findViewById(R.id.tv_oct_value),
            btnMinus = overlayView.findViewById(R.id.btn_oct_minus),
            btnPlus = overlayView.findViewById(R.id.btn_oct_plus),
            btnReset = overlayView.findViewById(R.id.btn_oct_reset),
            key = getString(R.string.key_pitchshift_octaves),
            min = -3, max = 3
        )
        setupRow(
            seekBar = overlayView.findViewById(R.id.seekbar_semitones),
            tvValue = overlayView.findViewById(R.id.tv_semi_value),
            btnMinus = overlayView.findViewById(R.id.btn_semi_minus),
            btnPlus = overlayView.findViewById(R.id.btn_semi_plus),
            btnReset = overlayView.findViewById(R.id.btn_semi_reset),
            key = getString(R.string.key_pitchshift_semitones),
            min = -12, max = 12
        )
        setupRow(
            seekBar = overlayView.findViewById(R.id.seekbar_cents),
            tvValue = overlayView.findViewById(R.id.tv_cents_value),
            btnMinus = overlayView.findViewById(R.id.btn_cents_minus),
            btnPlus = overlayView.findViewById(R.id.btn_cents_plus),
            btnReset = overlayView.findViewById(R.id.btn_cents_reset),
            key = getString(R.string.key_pitchshift_cents),
            min = -100, max = 100
        )
    }

    private fun setupRow(
        seekBar: SeekBar,
        tvValue: TextView,
        btnMinus: ImageButton,
        btnPlus: ImageButton,
        btnReset: ImageButton,
        key: String,
        min: Int,
        max: Int
    ) {
        val current = prefs.getFloat(key, 0f).roundToInt().coerceIn(min, max)
        seekBar.min = min
        seekBar.max = max
        seekBar.progress = current
        tvValue.text = current.toString()

        fun save(value: Int) {
            val clamped = value.coerceIn(min, max)
            prefs.edit().putFloat(key, clamped.toFloat()).apply()
            tvValue.text = clamped.toString()
            seekBar.progress = clamped
            sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) save(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        btnMinus.setOnClickListener { save(seekBar.progress - 1) }
        btnPlus.setOnClickListener { save(seekBar.progress + 1) }
        btnReset.setOnClickListener { save(0) }
    }

    private fun setupDrag(handle: View) {
        var startRawX = 0f; var startRawY = 0f
        var startWinX = 0; var startWinY = 0
        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX; startRawY = event.rawY
                    startWinX = params.x; startWinY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startWinX + (event.rawX - startRawX).roundToInt()
                    params.y = startWinY + (event.rawY - startRawY).roundToInt()
                    windowManager.updateViewLayout(overlayView, params)
                    true
                }
                else -> false
            }
        }
    }

    private fun setupCollapsedPill(collapsedView: View, expandedView: View) {
        var startRawX = 0f; var startRawY = 0f
        var startWinX = 0; var startWinY = 0
        val tapThreshSq = (8 * resources.displayMetrics.density).let { it * it }

        collapsedView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX; startRawY = event.rawY
                    startWinX = params.x; startWinY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX; val dy = event.rawY - startRawY
                    if (dx * dx + dy * dy > tapThreshSq) {
                        params.x = startWinX + dx.roundToInt()
                        params.y = startWinY + dy.roundToInt()
                        windowManager.updateViewLayout(overlayView, params)
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
        runCatching { windowManager.removeView(overlayView) }
    }

    companion object {
        @Volatile var isRunning = false
    }
}
