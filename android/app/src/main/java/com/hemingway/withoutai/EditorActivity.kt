package com.hemingway.withoutai

/**
 * The paste-anything fallback, opened from [HomeActivity].
 *
 * For apps that draw their own text-selection menu — Obsidian is the known case
 * — Android's toolbar never appears, so no `PROCESS_TEXT` entry can be offered
 * and the share sheet may be the only other way out. Copying and pasting here
 * works from anywhere.
 *
 * It is the same screen as [ProcessTextActivity] rather than a second editor:
 * started with no incoming text, the base class shows the page's demo text and
 * hides Replace, since there is nowhere to write back to.
 */
class EditorActivity : ProcessTextActivity()
