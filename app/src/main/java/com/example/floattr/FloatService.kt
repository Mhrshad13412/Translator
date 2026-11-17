package com.example.floattr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class FloatService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.floattr.STOP"
        const val ACTION_PROJECTION = "com.example.floattr.PROJECTION"
        const val ACTION_PROJECTION_DENIED = "com.example.floattr.PROJECTION_DENIED"
        private const val CHANNEL = "float_tr"
    }

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val lock = Any()

    private lateinit var bubble: TextView
    private lateinit var bubbleLp: WindowManager.LayoutParams
    private lateinit var panel: LinearLayout
    private lateinit var panelLp: WindowManager.LayoutParams
    private lateinit var input: EditText
    private lateinit var output: TextView
    private lateinit var dirBtn: TextView
    private lateinit var status: TextView

    private var sl = "en"
    private var tl = "fa"
    private var reqId = 0
    private var expanded = false

    // screen capture
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vd: VirtualDisplay? = null
    private var lastImage: Image? = null
    private var pendingRead = false
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private val debounce = Runnable { translateInput() }
    private val fade = Runnable { bubble.animate().alpha(0.45f).setDuration(400).start() }
    private val hideNotice = Runnable { status.visibility = View.GONE }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        buildBubble()
        buildPanel()
        wm.addView(bubble, bubbleLp)
        wake()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_PROJECTION -> {
                val code = intent.getIntExtra("code", 0)
                val data = intent.getParcelableExtra("data", Intent::class.java)
                if (data != null) {
                    startForeground(
                        1, buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                    try {
                        setupProjection(code, data)
                        if (pendingRead) {
                            pendingRead = false
                            main.postDelayed({ captureAndTranslate() }, 700)
                        }
                    } catch (e: Exception) {
                        notice("⚠ " + (e.message ?: "capture error"))
                    }
                }
            }
            ACTION_PROJECTION_DENIED -> {
                pendingRead = false
                notice("اجازه‌ی خوندن صفحه داده نشد")
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        try { if (expanded) wm.removeView(panel) } catch (_: Exception) {}
        try { wm.removeView(bubble) } catch (_: Exception) {}
        releaseProjection()
        io.shutdownNow()
        try { recognizer.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Floating translator", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, FloatService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("مترجم شناور فعاله")
            .setContentText("برای بستن، همین‌جا بزن")
            .setContentIntent(stop)
            .setOngoing(true)
            .build()
    }

    // ---------------------------------------------------------------- UI

    private fun chip(t: String, sp: Float = 16f) = TextView(this).apply {
        text = t
        textSize = sp
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(6), dp(10), dp(6))
    }

    private fun buildBubble() {
        bubble = TextView(this).apply {
            text = "文A"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF1E9E6A.toInt())
            }
        }
        bubbleLp = WindowManager.LayoutParams(
            dp(52), dp(52),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(220)
        }
        makeDraggable(bubble, bubbleLp, bubble) { expand() }
    }

    private fun buildPanel() {
        panel = object : LinearLayout(this@FloatService) {
            override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                if (e.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (e.action == KeyEvent.ACTION_UP) collapse()
                    return true
                }
                return super.dispatchKeyEvent(e)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xF0202124.toInt())
            }
        }

        // header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val move = chip("✥")
        dirBtn = chip("EN → FA", 14f).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0x33FFFFFF)
            }
        }
        val readBtn = chip("📖")
        val minBtn = chip("—")
        val closeBtn = chip("✕")
        header.addView(move)
        header.addView(dirBtn)
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(readBtn)
        header.addView(minBtn)
        header.addView(closeBtn)
        panel.addView(header)

        // input
        input = EditText(this).apply {
            setTextColor(Color.WHITE)
            setHintTextColor(0x99FFFFFF.toInt())
            textSize = 15f
            minLines = 2
            maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0x22FFFFFF)
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    main.removeCallbacks(debounce)
                    main.postDelayed(debounce, 600)
                }
            })
        }
        panel.addView(input, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })

        // output
        output = TextView(this).apply {
            setTextColor(0xFF8BE3B5.toInt())
            textSize = 16f
            maxLines = 8
            movementMethod = ScrollingMovementMethod()
            setPadding(dp(4), dp(8), dp(4), dp(2))
            setOnClickListener {
                val t = text.toString()
                if (t.isNotBlank()) {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("translation", t))
                    notice("کپی شد")
                }
            }
        }
        panel.addView(output, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // status line (small notices inside the panel)
        status = TextView(this).apply {
            setTextColor(0xFFBBBBBB.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, dp(6), 0, 0)
        }
        panel.addView(status, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        panelLp = WindowManager.LayoutParams(
            dp(290), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }

        makeDraggable(move, panelLp, panel, null)
        minBtn.setOnClickListener { collapse() }
        closeBtn.setOnClickListener { stopSelf() }
        readBtn.setOnClickListener { onReadClick() }
        dirBtn.setOnClickListener {
            val t = sl; sl = tl; tl = t
            updateDirUi()
            translateInput()
        }
        updateDirUi()
    }

    private fun updateDirUi() {
        dirBtn.text = "${sl.uppercase()} → ${tl.uppercase()}"
        input.hint = if (sl == "fa") "اینجا تایپ کن…" else "Type here…"
        input.textDirection = if (sl == "fa") View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_LTR
        input.gravity = if (sl == "fa") Gravity.END or Gravity.TOP else Gravity.START or Gravity.TOP
        output.textDirection = if (tl == "fa") View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_LTR
        output.gravity = if (tl == "fa") Gravity.END else Gravity.START
    }

    private fun makeDraggable(
        handle: View, lp: WindowManager.LayoutParams, target: View, onTap: (() -> Unit)?
    ) {
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false
                    if (target === bubble) wake()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx
                    val dy = e.rawY - sy
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) moved = true
                    if (moved) {
                        lp.x = (ox + dx.toInt()).coerceAtLeast(0)
                        lp.y = (oy + dy.toInt()).coerceAtLeast(0)
                        try { wm.updateViewLayout(target, lp) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onTap?.invoke()
                    if (target === bubble) wake()
                    true
                }
                else -> false
            }
        }
    }

    private fun wake() {
        bubble.animate().cancel()
        bubble.alpha = 1f
        main.removeCallbacks(fade)
        main.postDelayed(fade, 2500)
    }

    private fun imm() = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    private fun expand() {
        if (expanded) return
        expanded = true
        val sw = resources.displayMetrics.widthPixels
        panelLp.x = bubbleLp.x.coerceIn(0, (sw - panelLp.width).coerceAtLeast(0))
        panelLp.y = bubbleLp.y
        bubble.visibility = View.GONE
        wm.addView(panel, panelLp)
        main.postDelayed({
            input.requestFocus()
            imm().showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    private fun collapse() {
        if (!expanded) return
        expanded = false
        imm().hideSoftInputFromWindow(input.windowToken, 0)
        bubbleLp.x = panelLp.x
        bubbleLp.y = panelLp.y
        try { wm.removeView(panel) } catch (_: Exception) {}
        bubble.visibility = View.VISIBLE
        try { wm.updateViewLayout(bubble, bubbleLp) } catch (_: Exception) {}
        wake()
    }

    private fun notice(msg: String) {
        status.text = msg
        status.visibility = View.VISIBLE
        main.removeCallbacks(hideNotice)
        main.postDelayed(hideNotice, 2000)
    }

    // ---------------------------------------------------------------- translate

    private fun translateInput() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) {
            reqId++
            output.text = ""
            return
        }
        runTranslate(text, sl)
    }

    private fun runTranslate(text: String, from: String) {
        val id = ++reqId
        val to = tl
        output.text = "…"
        io.execute {
            val res = try {
                googleTranslate(text, from, to)
            } catch (e: Exception) {
                "⚠ " + (e.message ?: "network error")
            }
            main.post { if (id == reqId) output.text = res }
        }
    }

    private fun chunkText(text: String, max: Int): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (line in text.split("\n")) {
            if (cur.isNotEmpty() && cur.length + line.length + 1 > max) {
                out.add(cur.toString()); cur.clear()
            }
            if (cur.isNotEmpty()) cur.append('\n')
            cur.append(line)
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    private fun googleTranslate(text: String, from: String, to: String): String {
        val sb = StringBuilder()
        for (chunk in chunkText(text, 1200)) {
            val u = URL(
                "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$from&tl=$to&dt=t&q=" +
                    URLEncoder.encode(chunk, "UTF-8")
            )
            val c = u.openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            try {
                val body = c.inputStream.bufferedReader().readText()
                val arr = JSONArray(body).getJSONArray(0)
                for (i in 0 until arr.length()) {
                    val part = arr.optJSONArray(i) ?: continue
                    sb.append(part.optString(0, ""))
                }
            } finally {
                c.disconnect()
            }
            sb.append('\n')
        }
        return sb.toString().trim()
    }

    // ---------------------------------------------------------------- read screen

    private fun onReadClick() {
        if (projection == null) {
            pendingRead = true
            startActivity(
                Intent(this, ProjectionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } else {
            captureAndTranslate()
        }
    }

    private fun setupProjection(code: Int, data: Intent) {
        releaseProjection()
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp: MediaProjection = mpm.getMediaProjection(code, data)
            ?: throw IllegalStateException("capture permission is not available")
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { releaseProjection() } }
        }, main)

        val b = wm.maximumWindowMetrics.bounds
        val w = b.width()
        val h = b.height()
        val dpi = resources.displayMetrics.densityDpi

        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ rd ->
            val img = try { rd.acquireLatestImage() } catch (_: Exception) { null }
            if (img != null) {
                synchronized(lock) {
                    lastImage?.close()
                    lastImage = img
                }
            }
        }, main)

        vd = mp.createVirtualDisplay(
            "floattr", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, main
        )
        projection = mp
        reader = r
    }

    private fun releaseProjection() {
        try { vd?.release() } catch (_: Exception) {}
        vd = null
        synchronized(lock) {
            try { lastImage?.close() } catch (_: Exception) {}
            lastImage = null
        }
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
    }

    private fun grabBitmap(): Bitmap? = synchronized(lock) {
        val img = lastImage ?: return@synchronized null
        val p = img.planes[0]
        val rowPad = p.rowStride - p.pixelStride * img.width
        val full = Bitmap.createBitmap(
            img.width + rowPad / p.pixelStride, img.height, Bitmap.Config.ARGB_8888
        )
        p.buffer.rewind()
        full.copyPixelsFromBuffer(p.buffer)
        if (full.width != img.width) Bitmap.createBitmap(full, 0, 0, img.width, img.height) else full
    }

    private fun captureAndTranslate() {
        if (!expanded) expand()
        // hide our own window so it is not part of the screenshot
        panel.visibility = View.INVISIBLE
        main.postDelayed({
            val bmp = try { grabBitmap() } catch (e: Exception) { null }
            panel.visibility = View.VISIBLE
            if (bmp == null) {
                output.text = "⚠ تصویر صفحه گرفته نشد، دوباره امتحان کن"
                return@postDelayed
            }
            output.text = "…"
            recognizer.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { res ->
                    bmp.recycle()
                    val text = res.textBlocks
                        .joinToString("\n") { it.text.replace("\n", " ") }
                        .trim()
                    if (text.isEmpty()) {
                        output.text = "متنی پیدا نشد"
                    } else {
                        runTranslate(text, "auto")
                    }
                }
                .addOnFailureListener { e ->
                    output.text = "⚠ " + (e.message ?: "OCR error")
                }
        }, 450)
    }
}
