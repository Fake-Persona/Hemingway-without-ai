package com.hemingway.withoutai

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView

/**
 * The app's home screen, and the reason it has a launcher icon at all.
 *
 * The two capabilities the live overlay needs can only be granted by the user
 * in system settings — no runtime permission dialog exists for either — so this
 * screen exists to explain what each is for and send you to the right page.
 * Same two grants Grammarly asks for.
 */
class SetupActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        findViewById<Button>(R.id.grant_overlay).setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }

        findViewById<Button>(R.id.grant_accessibility).setOnClickListener {
            // There is no way to deep-link to one service's own toggle, so this
            // opens the accessibility list and the user picks "Hemingway".
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    // Status is re-read on resume so returning from settings reflects reality
    // rather than whatever was true when the screen was first drawn.
    override fun onResume() {
        super.onResume()
        renderStatus()
    }

    private fun renderStatus() {
        val overlay = canDrawOverlays(this)
        val accessibility = isAccessibilityServiceEnabled(this)

        findViewById<TextView>(R.id.overlay_status).text =
            getString(if (overlay) R.string.granted else R.string.not_granted)
        findViewById<TextView>(R.id.accessibility_status).text =
            getString(if (accessibility) R.string.granted else R.string.not_granted)

        findViewById<TextView>(R.id.overall_status).setText(
            when {
                overlay && accessibility -> R.string.status_ready
                else -> R.string.status_incomplete
            }
        )
    }

    companion object {
        fun canDrawOverlays(context: Context): Boolean {
            // Below API 23 the permission is granted at install time.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
            return Settings.canDrawOverlays(context)
        }

        /**
         * Reads the enabled-services list from Settings rather than tracking our
         * own flag, so it stays correct when the user disables the service from
         * system settings without ever reopening this app.
         */
        fun isAccessibilityServiceEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()

            val target = "${context.packageName}/${HemingwayAccessibilityService::class.java.name}"
            return enabled.split(':').any { it.equals(target, ignoreCase = true) }
        }
    }
}
