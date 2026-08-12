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
 * The launcher screen: turn live feedback on, or fall back to pasting text in.
 *
 * The two capabilities the floating panel needs can only be granted by the user
 * in system settings — Android offers no runtime dialog for either — so this
 * screen explains what each is for and opens the right page. Same two grants
 * Grammarly asks for.
 *
 * Having any launcher activity is also load-bearing: a freshly installed app
 * stays in Android's "stopped" state until opened once, and a stopped app's
 * components are skipped when the system resolves intents — which is why the
 * selection-toolbar entry never appeared before this existed.
 */
class HomeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        findViewById<Button>(R.id.grant_overlay).setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }

        findViewById<Button>(R.id.grant_accessibility).setOnClickListener {
            // Android offers no way to deep-link a single service's toggle, so
            // this opens the list and the user picks "Hemingway".
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.open_editor).setOnClickListener {
            startActivity(Intent(this, EditorActivity::class.java))
        }
    }

    // Re-read on resume so returning from settings shows what is actually true,
    // not what was true when the screen was first drawn.
    override fun onResume() {
        super.onResume()
        renderStatus()
    }

    private fun renderStatus() {
        val overlay = canDrawOverlays(this)
        val accessibility = isAccessibilityServiceEnabled(this)

        findViewById<TextView>(R.id.overlay_status)
            .setText(if (overlay) R.string.granted else R.string.not_granted)
        findViewById<TextView>(R.id.accessibility_status)
            .setText(if (accessibility) R.string.granted else R.string.not_granted)
        findViewById<TextView>(R.id.overall_status)
            .setText(if (overlay && accessibility) R.string.status_ready else R.string.status_incomplete)
    }

    companion object {
        fun canDrawOverlays(context: Context): Boolean {
            // Below API 23 the permission is granted at install time.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
            return Settings.canDrawOverlays(context)
        }

        /**
         * Reads the enabled-services list from Settings rather than tracking our
         * own flag, so it stays correct when the service is switched off in
         * system settings without this app ever being reopened.
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
