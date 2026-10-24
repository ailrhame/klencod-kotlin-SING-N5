package com.sing.dpl

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
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
import android.media.audiofx.Visualizer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

// ============================================================
// SPECTRUM — shared visualizer buffer + simulated instruments
// ============================================================
object Spectrum {
    const val BANDS = 24
    val magnitudes = FloatArray(BANDS)
    val smoothed = FloatArray(BANDS)
    @Volatile var peak = 0f
    @Volatile var instrumentMix = 0
    val instrumentNames = arrayOf("PIANO", "GUITAR", "OUD", "VIOLIN", "SYNTH")

    fun push(data: ByteArray) {
        for (i in magnitudes.indices) magnitudes[i] = 0f
        if (data.isEmpty()) return
        val chunk = data.size / BANDS
        if (chunk <= 0) return
        for (b in 0 until BANDS) {
            var sum = 0
            val start = b * chunk
            val end = min(start + chunk, data.size)
            for (i in start until end) {
                val v = (data[i].toInt() and 0xFF) - 128
                sum += abs(v)
            }
            val avg = sum.toFloat() / (end - start).coerceAtLeast(1)
            magnitudes[b] = (avg / 128f).coerceIn(0f, 1f)
        }
        var sum = 0f
        for (m in magnitudes) sum += m
        peak = (sum / BANDS).coerceIn(0f, 1f)
    }

    fun smooth(dt: Float) {
        val k = (dt * 8f).coerceIn(0f, 1f)
        for (i in smoothed.indices) {
            smoothed[i] += (magnitudes[i] - smoothed[i]) * k
        }
    }
}

// ============================================================
// INSTRUMENT ENGINE — synthesizes tones that follow the spectrum
// ============================================================
class InstrumentEngine {
    @Volatile var enabled = true
    @Volatile var mix = 0
    @Volatile var volume = 0.5f
    private val sampleRate = 44100
    private val handler = Handler(Looper.getMainLooper())
    private val voices = ArrayList<Voice>()
    private var running = false
    private val rnd = Random(System.currentTimeMillis())

    inner class Voice(val freq: Float, val wave: Int, var life: Float, val phase0: Float, val amp0: Float) {
        var phase = phase0
        var amp = amp0
    }

    fun start() {
        if (running) return
        running = true
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        synchronized(voices) { voices.clear() }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            driveVoices()
            handler.postDelayed(this, 90)
        }
    }

    private fun driveVoices() {
        if (!enabled) return
        val avg = Spectrum.peak
        if (avg < 0.08f) return
        val baseFreqs = floatArrayOf(
            130.81f, 146.83f, 164.81f, 174.61f, 196.00f, 220.00f,
            246.94f, 261.63f, 293.66f, 329.63f, 349.23f, 392.00f
        )
        val count = (1 + (avg * 6).toInt()).coerceAtMost(6)
        synchronized(voices) {
            for (i in 0 until count) {
                if (voices.size > 24) break
                val f = baseFreqs[rnd.nextInt(baseFreqs.size)]
                val wave = mix
                voices.add(Voice(f, wave, 0.9f, 0f, 0.05f + avg * 0.4f))
            }
        }
    }

    fun render(buffer: ShortArray, count: Int) {
        if (!enabled) {
            java.util.Arrays.fill(buffer, 0, count, 0)
            return
        }
        val vol = volume
        val dt = count.toFloat() / sampleRate
        synchronized(voices) {
            val iter = voices.iterator()
            while (iter.hasNext()) {
                val v = iter.next()
                v.life -= dt * 1.8f
                if (v.life <= 0f) { iter.remove(); continue }
                val w = when (v.wave) {
                    0 -> sinPhase(v, "sine")
                    1 -> sinPhase(v, "saw")
                    2 -> sinPhase(v, "pluck")
                    3 -> sinPhase(v, "square")
                    else -> sinPhase(v, "synth")
                }
                v.amp *= (1f - dt * 0.6f)
                val s = (w * v.amp * vol * Short.MAX_VALUE).toInt()
                buffer[count - 1] = (buffer[count - 1].toInt() + s / 4).toInt().coerceIn(-32767, 32767).toShort()
                if (count > 1) {
                    buffer[count - 2] = (buffer[count - 2].toInt() + s / 8).toInt().coerceIn(-32767, 32767).toShort()
                }
            }
        }
        val dtStep = 2.0 * Math.PI / sampleRate
        synchronized(voices) {
            for (v in voices) {
                v.phase += dtStep.toFloat() * v.freq
                if (v.phase > Math.PI.toFloat() * 2) v.phase -= (Math.PI.toFloat() * 2)
            }
        }
    }

    private fun sinPhase(v: Voice, kind: String): Float {
        val p = v.phase
        return when (kind) {
            "sine" -> sin(p.toDouble()).toFloat()
            "saw" -> (p / Math.PI.toFloat()) - 1f
            "pluck" -> ((1f - v.life).coerceIn(0f, 1f) * sin(p.toDouble() * (1f + v.life * 3f))).toFloat()
            "square" -> if (sin(p.toDouble()) >= 0) 0.7f else -0.7f
            else -> (sin(p.toDouble()) * 0.7 + sin(p.toDouble() * 2.0) * 0.3).toFloat()
        }
    }

    fun noteName(idx: Int): String = when (idx) {
        0 -> "PIANO"
        1 -> "GUITAR"
        2 -> "OUD"
        3 -> "VIOLIN"
        else -> "SYNTH"
    }
}

// ============================================================
// VISUALIZER CANVAS — spectrum bars + floating instruments
// ============================================================
class SpectrumView(context: Context) : View(context) {
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val notes = ArrayList<Float>()
    private var lastTick = System.currentTimeMillis()
    private val rnd = Random(System.currentTimeMillis())
    private val instruments = arrayOf("🎹", "🎸", "🪕", "🎻", "🎛")

    init {
        barPaint.style = Paint.Style.FILL
        glowPaint.style = Paint.Style.FILL
        labelPaint.color = Color.parseColor("#8899AA")
        labelPaint.textSize = 22f
        labelPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        labelPaint.textAlign = Paint.Align.CENTER
        orbPaint.style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val now = System.currentTimeMillis()
        val dt = ((now - lastTick).coerceAtMost(100) / 1000f)
        lastTick = now
        Spectrum.smooth(dt)

        val bg = LinearGradient(0f, 0f, 0f, h,
            intArrayOf(Color.parseColor("#05060A"), Color.parseColor("#0A1020"), Color.parseColor("#05060A")),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        bgPaint.shader = bg
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val orbCx = w / 2f
        val orbCy = h * 0.28f
        val orbR = min(w, h) * 0.28f
        val pulse = Spectrum.peak
        val radial = RadialGradient(orbCx, orbCy, orbR * (1f + pulse * 0.6f),
            intArrayOf(
                Color.parseColor("#7B2FFF"),
                Color.parseColor("#3B1E8B"),
                Color.parseColor("#0B0A1A")
            ),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        orbPaint.shader = radial
        canvas.drawCircle(orbCx, orbCy, orbR, orbPaint)

        for (i in 0 until 3) {
            val r = orbR * (1.05f + i * 0.18f + pulse * 0.35f)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f + i * 1.2f
                color = Color.argb(90 - i * 20, 123, 47, 255)
            }
            canvas.drawCircle(orbCx, orbCy, r, p)
        }

        val instrPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = orbR * 0.5f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(instruments[Spectrum.instrumentMix % instruments.size],
            orbCx, orbCy + instrPaint.textSize * 0.35f, instrPaint)

        val barTop = h * 0.62f
        val barH = h * 0.30f
        val barW = w / (Spectrum.BANDS * 2f)
        for (i in 0 until Spectrum.BANDS) {
            val mag = Spectrum.smoothed[i]
            val bh = (mag * barH).coerceAtLeast(2f)
            val x = (i * 2 + 1) * barW
            val y = barTop + (barH - bh)
            val hue = 240f + i * 4f
            val c1 = Color.HSVToColor(floatArrayOf(hue % 360f, 0.85f, 0.95f))
            val grad = LinearGradient(x, y, x, barTop + barH,
                intArrayOf(c1, Color.argb(40, Color.red(c1), Color.green(c1), Color.blue(c1))),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            barPaint.shader = grad
            val r = RectF(x + 1f, y, x + barW - 2f, barTop + barH)
            canvas.drawRoundRect(r, 6f, 6f, barPaint)
        }

        labelPaint.textSize = 26f
        labelPaint.color = Color.parseColor("#AABBDD")
        canvas.drawText("SPECTRUM", w / 2f, h - 24f, labelPaint)

        val activeInstr = Spectrum.instrumentNames[Spectrum.instrumentMix % Spectrum.instrumentNames.size]
        labelPaint.color = Color.parseColor("#7B2FFF")
        labelPaint.textSize = 30f
        canvas.drawText("ACTIVE: $activeInstr", w / 2f, h * 0.56f, labelPaint)

        if (rnd.nextFloat() < Spectrum.peak * 0.4f) {
            notes.add(orbCx + (rnd.nextFloat() - 0.5f) * orbR * 1.4f)
        }
        val iter = notes.iterator()
        var idx = 0
        while (iter.hasNext()) {
            val nx = iter.next()
            val ny = orbCy + orbR * 0.2f + idx * 30f + (System.currentTimeMillis() % 1000) * 0.04f
            labelPaint.color = Color.argb(200, 200, 200, 255)
            labelPaint.textSize = 28f
            canvas.drawText("♪", nx, ny, labelPaint)
            idx++
        }
        if (notes.size > 12) notes.removeAt(0)

        postInvalidateOnAnimation()
    }
}

// ============================================================
// SYNTH RECEIVER — broadcast between service & UI
// ============================================================
class SynthReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            "com.sing.dpl.STATE" -> {
                Spectrum.instrumentMix = intent.getIntExtra("mix", 0)
            }
        }
    }
}

// ============================================================
// SYNTH SERVICE — wraps playback + visualizer + synth engine
// ============================================================
class MusicSynthService : Service() {
    private var mediaPlayer: MediaPlayer? = null
    private var visualizer: Visualizer? = null
    private var engine: InstrumentEngine? = null
    private var trackUri: Uri? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        engine = InstrumentEngine().also { it.start() }
        notificationSetup()
    }

    private fun notificationSetup() {
        val chId = "sing_n5_synth"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(chId, "SING N5", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n: Notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, chId)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
            .setContentTitle("SING N5")
            .setContentText("الآلات تعزف مع الأغنية")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(77, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(77, n)
            }
        } catch (e: Exception) { }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra("uri")?.let { uriStr ->
            trackUri = Uri.parse(uriStr)
            startPlayback(trackUri!!)
        }
        intent?.getIntExtra("mix", -1)?.let { if (it >= 0) setMix(it) }
        intent?.getFloatExtra("volume", -1f)?.let { if (it >= 0) setVolume(it) }
        return START_STICKY
    }

    private fun startPlayback(uri: Uri) {
        stopPlayback()
        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(this@MusicSynthService, uri)
                prepare()
                start()
            }
            visualizer = Visualizer(mediaPlayer!!.audioSessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(v: Visualizer?, data: ByteArray?, rate: Int) {
                            if (data != null) Spectrum.push(data)
                        }
                        override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) {
                        }
                    },
                    Visualizer.getMaxCaptureRate() / 2,
                    true,
                    false
                )
                enabled = true
            }
            broadcastState()
        } catch (e: Exception) {
            handler.post {
                Toast.makeText(applicationContext, "تعذر تشغيل الملف", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun stopPlayback() {
        try { visualizer?.enabled = false } catch (e: Exception) {}
        try { visualizer?.release() } catch (e: Exception) {}
        visualizer = null
        try { mediaPlayer?.stop() } catch (e: Exception) {}
        try { mediaPlayer?.release() } catch (e: Exception) {}
        mediaPlayer = null
    }

    fun setMix(idx: Int) {
        val e = engine ?: return
        e.mix = idx
        Spectrum.instrumentMix = idx
        broadcastState()
    }

    fun setVolume(v: Float) {
        engine?.volume = v.coerceIn(0f, 1f)
    }

    private fun broadcastState() {
        val i = Intent("com.sing.dpl.STATE").setPackage(packageName)
        i.putExtra("mix", Spectrum.instrumentMix)
        sendBroadcast(i)
    }

    override fun onDestroy() {
        stopPlayback()
        engine?.stop()
        engine = null
        super.onDestroy()
    }
}

// ============================================================
// MAIN ACTIVITY — luxury UI
// ============================================================
class MainActivity : Activity() {
    private val REQ_AUDIO = 7001
    private lateinit var pickBtn: Button
    private lateinit var playBtn: Button
    private lateinit var statusTxt: TextView
    private lateinit var spectrumView: SpectrumView
    private lateinit var instrumentButtons: List<Button>
    private lateinit var volumeSeek: SeekBar
    private var trackUri: Uri? = null
    private var receiver: SynthReceiver? = null
    private var playing = false

    private val GOLD = Color.parseColor("#D4AF37")
    private val PURPLE = Color.parseColor("#7B2FFF")
    private val DEEP_BG = Color.parseColor("#05060A")
    private val CARD_BG = Color.parseColor("#111524")
    private val TEXT_LIGHT = Color.parseColor("#E8ECF4")
    private val TEXT_MUTED = Color.parseColor("#8899AA")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerSynthReceiver()
        buildUI()
        requestAudioPermissions()
        startSynthService()
    }

    private fun registerSynthReceiver() {
        receiver = SynthReceiver()
        val f = IntentFilter("com.sing.dpl.STATE")
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION") registerReceiver(receiver, f)
        }
    }

    private fun startSynthService() {
        val i = Intent(this, MusicSynthService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } catch (e: Exception) { }
    }

    private fun requestAudioPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val needed = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), REQ_AUDIO)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                statusTxt.text = "اختر أغنية للبدء"
            } else {
                statusTxt.text = "الصلاحية مرفوضة — يمكنك اختيار ملف مباشرة من المستكشف"
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int, stroke: Int = 0, strokeColor: Int = Color.TRANSPARENT): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dp(radius).toFloat()
            setColor(color)
            if (stroke > 0) setStroke(dp(stroke), strokeColor)
        }
    }

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(DEEP_BG)
            setPadding(dp(16), dp(20), dp(16), dp(16))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        val logo = TextView(this).apply {
            text = "SING N5"
            textSize = 22f
            setTextColor(GOLD)
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            letterSpacing = 0.18f
        }
        val sub = TextView(this).apply {
            text = "  ·  AI Instrumental Synth"
            textSize = 11f
            setTextColor(TEXT_MUTED)
            typeface = Typeface.MONOSPACE
        }
        topBar.addView(logo)
        topBar.addView(sub)
        root.addView(topBar)

        spectrumView = SpectrumView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val spectrumWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(2))
            background = rounded(CARD_BG, 20, 1, Color.parseColor("#1C2340"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).also {
                it.bottomMargin = dp(14)
            }
            addView(spectrumView)
        }
        root.addView(spectrumWrap)

        val instrumentsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(10))
        }
        val instrLabels = listOf("🎹 PIANO", "🎸 GUITAR", "🪕 OUD", "🎻 VIOLIN", "🎛 SYNTH")
        val buttons = mutableListOf<Button>()
        for ((idx, label) in instrLabels.withIndex()) {
            val b = Button(this).apply {
                text = label
                setTextColor(TEXT_LIGHT)
                textSize = 10f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                background = rounded(CARD_BG, 12, 1, Color.parseColor("#1C2340"))
                setPadding(dp(6), dp(8), dp(6), dp(8))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also {
                    it.marginEnd = dp(4)
                }
                setOnClickListener {
                    selectInstrument(idx)
                }
            }
            buttons.add(b)
            instrumentsRow.addView(b)
        }
        instrumentButtons = buttons
        root.addView(instrumentsRow)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(CARD_BG, 16, 1, Color.parseColor("#1C2340"))
        }

        val volRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        volRow.addView(TextView(this).apply {
            text = "VOL"
            setTextColor(GOLD)
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, dp(8), 0)
        })
        volumeSeek = SeekBar(this).apply {
            max = 100
            progress = 50
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val vol = p / 100f
                    val i = Intent(this@MainActivity, MusicSynthService::class.java)
                    i.putExtra("volume", vol)
                    try { startService(i) } catch (e: Exception) {}
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        volRow.addView(volumeSeek)
        controls.addView(volRow)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        pickBtn = Button(this).apply {
            text = "اختر أغنية 🎵"
            setTextColor(DEEP_BG)
            setBackgroundColor(GOLD)
            textSize = 14f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            background = rounded(GOLD, 12)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also {
                it.marginEnd = dp(8)
            }
            setOnClickListener { pickAudio() }
        }
        playBtn = Button(this).apply {
            text = "عزف ▶"
            setTextColor(TEXT_LIGHT)
            textSize = 14f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            background = rounded(PURPLE, 12)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { togglePlay() }
        }
        btnRow.addView(pickBtn)
        btnRow.addView(playBtn)
        controls.addView(btnRow)

        statusTxt = TextView(this).apply {
            text = "اختر أغنية لتبدأ الآلات بالعزف معها"
            setTextColor(TEXT_MUTED)
            textSize = 11f
            gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(10), 0, 0)
        }
        controls.addView(statusTxt)

        root.addView(controls)

        setContentView(root)
        updateInstrumentHighlight(0)
    }

    private fun updateInstrumentHighlight(selected: Int) {
        instrumentButtons.forEachIndexed { idx, b ->
            b.background = if (idx == selected)
                rounded(PURPLE, 12, 2, GOLD)
            else rounded(CARD_BG, 12, 1, Color.parseColor("#1C2340"))
        }
    }

    private fun selectInstrument(idx: Int) {
        Spectrum.instrumentMix = idx
        updateInstrumentHighlight(idx)
        val i = Intent(this, MusicSynthService::class.java)
        i.putExtra("mix", idx)
        try { startService(i) } catch (e: Exception) {}
        statusTxt.text = "الآلة النشطة: ${Spectrum.instrumentNames[idx]}"
    }

    private fun pickAudio() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "audio/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try { startActivityForResult(i, 7002) }
        catch (e: Exception) { Toast.makeText(this, "لا يوجد تطبيق لاختيار الأغاني", Toast.LENGTH_SHORT).show() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7002 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            trackUri = uri
            statusTxt.text = "تم اختيار الأغنية — اضغط عزف"
            playing = false
            playBtn.text = "عزف ▶"
        }
    }

    private fun togglePlay() {
        val uri = trackUri ?: run {
            pickAudio()
            return
        }
        if (!playing) {
            val i = Intent(this, MusicSynthService::class.java).apply {
                putExtra("uri", uri.toString())
                putExtra("mix", Spectrum.instrumentMix)
            }
            try { startService(i) } catch (e: Exception) {}
            playing = true
            playBtn.text = "إيقاف ⏸"
            statusTxt.text = "الآلات تعزف الآن..."
        } else {
            val i = Intent(this, MusicSynthService::class.java)
            stopService(i)
            playing = false
            playBtn.text = "عزف ▶"
            statusTxt.text = "تم الإيقاف"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { receiver?.let { unregisterReceiver(it) } } catch (e: Exception) {}
    }
}