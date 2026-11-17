package com.example.floattr

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val title = TextView(this).apply {
            text = "مترجم شناور"
            textSize = 24f
        }
        val info = TextView(this).apply {
            text = "یه دایره‌ی کوچیک روی همه‌ی برنامه‌ها میاد. بزنی باز میشه، توش تایپ می‌کنی و ترجمه‌ی گوگل رو می‌گیری.\n\n" +
                "دکمه‌ی 📖 بالای پنجره صفحه رو می‌خونه و ترجمه می‌کنه.\n" +
                "دکمه‌ی «—» پنجره رو دوباره به دایره تبدیل می‌کنه.\n" +
                "دکمه‌ی «EN → FA» جهت ترجمه رو عوض می‌کنه."
            textSize = 15f
            setPadding(0, pad, 0, pad)
        }
        status = TextView(this).apply {
            textSize = 14f
            visibility = View.GONE
            setPadding(0, 0, 0, pad)
        }
        val start = Button(this).apply {
            text = "شروع"
            setOnClickListener { startFloat() }
        }
        val stop = Button(this).apply {
            text = "توقف"
            setOnClickListener { stopService(Intent(this@MainActivity, FloatService::class.java)) }
        }
        root.addView(title)
        root.addView(info)
        root.addView(status)
        root.addView(start)
        root.addView(stop)
        setContentView(root)
    }

    private fun startFloat() {
        if (!Settings.canDrawOverlays(this)) {
            status.text = "اجازه‌ی «نمایش روی برنامه‌های دیگه» را بده، بعد برگرد و دوباره «شروع» را بزن"
            status.visibility = View.VISIBLE
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        startForegroundService(Intent(this, FloatService::class.java))
        finish()
    }
}
