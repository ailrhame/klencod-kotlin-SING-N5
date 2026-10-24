package com.sing.dpl

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// ============================================================
// AUDIO ENGINE — Dual MediaPlayer + Auto-Mix Simulation
// ============================================================
object AudioEngine {
    var deckA: MediaPlayer? = null
    var deckB: MediaPlayer? = null
    val waveA = FloatArray(60)
    val waveB = FloatArray(60)
    var bpmA = 128
    var bpmB = 126
    var crossfader = 0.5f // 0 = Deck A, 1 = Deck B
    var isPlayingA = false
    var isPlayingB = false

    fun loadDeck(isDeckA: Boolean, uri: Uri, context: android.content.Context) {
        try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                setDataSource(context, uri)
                prepare()
            }
            if (isDeckA) {
                deckA?.release()
                deckA = mp
                bpmA = 120 + Random.nextInt(20)
            } else {
                deckB?.release()
                deckB = mp
                bpmB = 120 + Random.nextInt(20)
            }
            updateVolume()
        } catch (e: Exception) {
            Toast.makeText(context, "خطأ في تحميل الملف", Toast.LENGTH_SHORT).show()
        }
    }

    fun togglePlay(isDeckA: Boolean) {
        val mp = if (isDeckA) deckA else deckB
        if (mp == null) return
        try {
            if (mp.isPlaying) {
                mp.pause()
                if (isDeckA) isPlayingA = false else isPlayingB = false
            } else {
                mp.start()
                if (isDeckA) isPlayingA = true else isPlayingB = true
            }
        } catch (e: Exception) {}
    }

    fun updateVolume() {
        try {
            deckA?.setVolume(1f - crossfader, 1f - crossfader)
            deckB?.setVolume(crossfader, crossfader)
        } catch (e: Exception) {}
    }

    fun tickWaves() {
        val rnd = Random(System.currentTimeMillis())
        for (i in 0 until 59) {
            waveA[i] = waveA[i + 1]
            waveB[i] = waveB[i + 1]
        }
        waveA[59] = if (isPlayingA) (rnd.nextFloat() * 0.8f + 0.1f) * (1f - crossfader) else 0f
        waveB[59] = if (isPlayingB) (rnd.nextFloat() * 0.8f + 0.1f) * crossfader else 0f
    }
}

// ============================================================
// MAIN ACTIVITY — Cinematic DJ Deck
// ============================================================
class MainActivity : Activity() {
    private var handler = Handler(Looper.getMainLooper())
    private lateinit var customView: DjInterfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestAudioPermissions()
        customView = DjInterfaceView(this)
        setContentView(customView)
        startTick()
    }

    private fun startTick() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                AudioEngine.tickWaves()
                customView.invalidate()
                handler.postDelayed(this, 50)
            }
        }, 50)
    }

    private fun requestAudioPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.READ_MEDIA_AUDIO), 100)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && data?.data != null) {
            val isDeckA = requestCode == 1
            AudioEngine.loadDeck(isDeckA, data.data!!, this)
        }
    }

    private fun pickAudio(isDeckA: Boolean) {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "audio/*" }
        startActivityForResult(intent, if (isDeckA) 1 else 2)
    }

    override fun onDestroy() {
        super.onDestroy()
        AudioEngine.deckA?.release()
        AudioEngine.deckB?.release()
    }

    // --------------------------------------------------------
    // CUSTOM CANVAS VIEW — Draws the entire DJ Interface
    // --------------------------------------------------------
    inner class DjInterfaceView(context: android.content.Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        
        private val ACCENT_CYAN = Color.parseColor("#00E5FF")
        private val ACCENT_GREEN = Color.parseColor("#76FF03")
        private val BG = Color.parseColor("#0A0A0C")
        private val PANEL = Color.parseColor("#141519")
        private val BTN_BG = Color.parseColor("#1C1E24")

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(BG)

            val w = width.toFloat()
            val h = height.toFloat()

            // Top Bars (Waveforms)
            drawWaveform(canvas, w / 2f - 20f, 40f, w / 2f - 40f, 80f, AudioEngine.waveA, "Blue Light", ACCENT_CYAN)
            drawWaveform(canvas, w - 40f, 40f, w / 2f - 40f, 80f, AudioEngine.waveB, "Euphoria", ACCENT_GREEN)

            // Decks
            drawDeck(canvas, w * 0.25f, h * 0.5f, 180f, true)
            drawDeck(canvas, w * 0.75f, h * 0.5f, 180f, false)

            // Center Mixer
            drawMixer(canvas, w * 0.5f, h * 0.5f)

            // Bottom Buttons
            drawBottomButtons(canvas, w, h)
        }

        private fun drawWaveform(canvas: Canvas, cx: Float, cy: Float, width: Float, height: Float, wave: FloatArray, title: String, color: Int) {
            textPaint.textSize = 20f
            textPaint.color = Color.WHITE
            canvas.drawText(title, cx - width/2 + 60f, cy - 10f, textPaint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = color
            val path = Path()
            val step = width / wave.size
            for (i in wave.indices) {
                val x = cx - width / 2f + i * step
                val y = cy + (wave[i] * height * 0.8f) - height / 2f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, paint)

            // Playhead
            paint.style = Paint.Style.FILL
            paint.color = Color.RED
            canvas.drawLine(cx, cy - height/2, cx, cy + height/2, paint)
        }

        private fun drawDeck(canvas: Canvas, cx: Float, cy: Float, radius: Float, isDeckA: Boolean) {
            val isPlaying = if (isDeckA) AudioEngine.isPlayingA else AudioEngine.isPlayingB
            val bpm = if (isDeckA) AudioEngine.bpmA else AudioEngine.bpmB
            val accent = if (isDeckA) ACCENT_CYAN else ACCENT_GREEN

            // Outer Ring
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 10f
            paint.color = BTN_BG
            canvas.drawCircle(cx, cy, radius, paint)
            
            paint.strokeWidth = 4f
            paint.color = accent
            canvas.drawCircle(cx, cy, radius - 5f, paint)

            // Center (Vinyl)
            val grad = RadialGradient(cx, cy, radius, intArrayOf(Color.DKGRAY, Color.BLACK), floatArrayOf(0.5f, 1f), Shader.TileMode.CLAMP)
            paint.style = Paint.Style.FILL
            paint.shader = grad
            canvas.drawCircle(cx, cy, radius - 20f, paint)
            paint.shader = null

            // BPM Text
            textPaint.textSize = 50f
            textPaint.color = Color.WHITE
            canvas.drawText("$bpm", cx, cy + 15f, textPaint)
            textPaint.textSize = 20f
            textPaint.color = Color.LTGRAY
            canvas.drawText("BPM", cx, cy + 40f, textPaint)

            // Play Button
            paint.color = if (isPlaying) ACCENT_GREEN else Color.DKGRAY
            canvas.drawCircle(cx, cy + radius + 40f, 30f, paint)
            textPaint.color = Color.BLACK
            textPaint.textSize = 30f
            canvas.drawText(if (isPlaying) "⏸" else "▶", cx, cy + radius + 50f, textPaint)
        }

        private fun drawMixer(canvas: Canvas, cx: Float, cy: Float) {
            // Vertical EQs
            for (i in 0 until 2) {
                val x = cx - 30f + i * 60f
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 4f
                paint.color = Color.DKGRAY
                canvas.drawLine(x, cy - 150f, x, cy + 50f, paint)

                // Knobs
                paint.style = Paint.Style.FILL
                paint.color = if (i == 0) ACCENT_GREEN else ACCENT_CYAN
                canvas.drawCircle(x, cy - 50f, 15f, paint)
                canvas.drawRect(x - 20f, cy + 20f, x + 20f, cy + 40f, paint)
            }

            // Crossfader
            val cx1 = cx - 80f
            val cx2 = cx + 80f
            val cyVal = cy + 120f
            paint.color = Color.DKGRAY
            paint.strokeWidth = 6f
            paint.style = Paint.Style.STROKE
            canvas.drawLine(cx1, cyVal, cx2, cyVal, paint)
            
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            val knobX = cx1 + (cx2 - cx1) * AudioEngine.crossfader
            canvas.drawRoundRect(RectF(knobX - 10f, cyVal - 20f, knobX + 10f, cyVal + 20f), 5f, 5f, paint)
        }

        private fun drawBottomButtons(canvas: Canvas, w: Float, h: Float) {
            val btnY = h - 60f
            val btnW = w / 8f
            
            // Simulating buttons visually
            paint.color = BTN_BG
            paint.style = Paint.Style.FILL
            for (i in 0 until 4) {
                // Left side
                canvas.drawRoundRect(RectF(20f + i * (btnW+10), btnY - 30f, 20f + (i+1)*(btnW+10), btnY + 30f), 10f, 10f, paint)
                // Right side
                canvas.drawRoundRect(RectF(w - 20f - (i+1)*(btnW+10), btnY - 30f, w - 20f - i*(btnW+10), btnY + 30f), 10f, 10f, paint)
            }
        }

        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                val x = event.x
                val y = event.y
                val w = width.toFloat()
                val h = height.toFloat()

                // Crossfader touch
                if (y > h * 0.5f + 80f && y < h * 0.5f + 160f) {
                    if (x > w * 0.5f - 100f && x < w * 0.5f + 100f) {
                        AudioEngine.crossfader = ((x - (w * 0.5f - 80f)) / 160f).coerceIn(0f, 1f)
                        AudioEngine.updateVolume()
                        invalidate()
                        return true
                    }
                }

                // Deck Play Buttons
                if (x < w / 2f && y > h * 0.5f + 180f && y < h * 0.5f + 250f) {
                    if (AudioEngine.deckA == null) pickAudio(true) else { AudioEngine.togglePlay(true); invalidate() }
                    return true
                }
                if (x > w / 2f && y > h * 0.5f + 180f && y < h * 0.5f + 250f) {
                    if (AudioEngine.deckB == null) pickAudio(false) else { AudioEngine.togglePlay(false); invalidate() }
                    return true
                }
            }
            return true
        }
    }
}