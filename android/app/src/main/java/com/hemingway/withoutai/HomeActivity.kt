package com.hemingway.withoutai

import android.app.Activity
import android.os.Bundle

/**
 * The app's launcher screen. It explains how to use Hemingway, because the
 * feature itself lives in the text-selection menu rather than in here.
 *
 * It also exists for a load-bearing reason: a freshly installed app stays in
 * Android's "stopped" state until the user opens it once, and a stopped app's
 * components are skipped when the system resolves intents. With no launcher
 * activity there was no way to open the app at all, so its PROCESS_TEXT
 * activity never became eligible for the selection toolbar. This screen is what
 * makes that first launch possible.
 */
class HomeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
    }
}
