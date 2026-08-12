package com.hemingway.withoutai

/**
 * The launcher entry, and the fallback for apps that offer no way out.
 *
 * Some editors — Obsidian among them — draw their own text-selection menu, so
 * Android's system toolbar never appears and a `PROCESS_TEXT` entry can never
 * be shown there. Nothing this app does can change that. What it can do is be
 * somewhere to paste into, which works from anywhere that can copy.
 *
 * It is deliberately the same screen as [ProcessTextActivity] rather than a
 * separate editor: launched with no incoming text, the base class shows the
 * page's own demo text and hides Replace, since there is nowhere to write back
 * to. Long-press and paste to analyse anything.
 *
 * Having any launcher activity is also load-bearing: a freshly installed app
 * stays in Android's "stopped" state until opened once, and a stopped app's
 * components are skipped when the system resolves intents — which is why the
 * selection-toolbar entry never appeared before this existed.
 */
class HomeActivity : ProcessTextActivity()
