package com.example.floattr

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/** Transparent activity whose only job is to ask for screen-capture permission. */
class ProjectionActivity : Activity() {

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), 1)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val i = Intent(this, FloatService::class.java)
        if (resultCode == RESULT_OK && data != null) {
            i.action = FloatService.ACTION_PROJECTION
            i.putExtra("code", resultCode)
            i.putExtra("data", data)
        } else {
            i.action = FloatService.ACTION_PROJECTION_DENIED
        }
        startService(i)
        finish()
    }
}
