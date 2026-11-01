package com.thirtytwo.steps

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class AppAudioActivity : AppCompatActivity() {

    private lateinit var prefs: PrefsManager
    private lateinit var volumeController: VolumeController
    private lateinit var listContainer: LinearLayout
    private lateinit var emptyView: TextView

    private data class AppInfo(
        val packageName: String,
        val label: String,
        val icon: Drawable?
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_audio)

        prefs = PrefsManager(this)
        volumeController = VolumeController.getInstance(this)
        listContainer = findViewById(R.id.app_audio_list)
        emptyView = findViewById(R.id.app_audio_empty)

        findViewById<View>(R.id.btn_separate_app_sound)?.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            openSeparateAppSoundSettings(this)
        }

        populateApps()
    }

    override fun onResume() {
        super.onResume()
        if (::listContainer.isInitialized) populateApps()
    }

    private fun populateApps() {
        listContainer.removeAllViews()
        val apps = prefs.recentAudioApps
            .filter { it != packageName }
            .mapNotNull { resolveAppInfo(it) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }

        if (apps.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            listContainer.visibility = View.GONE
            return
        }

        emptyView.visibility = View.GONE
        listContainer.visibility = View.VISIBLE
        val inflater = LayoutInflater.from(this)

        for (app in apps) {
            val row = inflater.inflate(R.layout.item_app_audio, listContainer, false)
            row.findViewById<ImageView>(R.id.app_audio_icon).setImageDrawable(app.icon)
            row.findViewById<TextView>(R.id.app_audio_name).text = app.label

            val slider = row.findViewById<SeekBar>(R.id.app_audio_slider)
            val value = row.findViewById<TextView>(R.id.app_audio_value)
            val initial = prefs.appVolumeMappings[app.packageName] ?: 100
            value.text = "$initial%"
            slider.max = 100
            slider.progress = initial

            slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    value.text = "$progress%"
                    volumeController.setAppVolume(app.packageName, progress)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    seekBar?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })

            listContainer.addView(row)
        }
    }

    private fun resolveAppInfo(packageName: String): AppInfo? {
        return try {
            val app = packageManager.getApplicationInfo(packageName, 0)
            AppInfo(
                packageName = packageName,
                label = packageManager.getApplicationLabel(app).toString(),
                icon = packageManager.getApplicationIcon(app)
            )
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    companion object {
        /**
         * Samsung documents Separate app sound under Settings > Sounds and vibration.
         * The public Android sound-settings action opens that Samsung settings area;
         * Android exposes no public API for changing Samsung's per-app routing rules.
         */
        fun openSeparateAppSoundSettings(context: Context) {
            val taskFlag = if (context !is Activity) Intent.FLAG_ACTIVITY_NEW_TASK else 0
            try {
                context.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS).addFlags(taskFlag))
            } catch (_: Exception) {
                try {
                    context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(taskFlag))
                } catch (_: Exception) {
                    Toast.makeText(context, "Open Settings > Sounds and vibration > Separate app sound", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
