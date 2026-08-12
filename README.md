# Hemingway (without AI)

A small, dependency-free readability editor. Write or paste text and it highlights, live, as you edit:

- **Adverbs** and weakening phrases (blue)
- **Passive voice** (green)
- **Complex words/phrases** with simpler alternatives on hover (purple)
- **Hard to read** sentences (yellow)
- **Very hard to read** sentences (red)

A sidebar shows counts for each category plus an overall grade level for the whole document, and there's a dark mode toggle.

All of the analysis is plain regex/word-list heuristics running entirely in the browser — no AI, no server, no network calls.

This started as a port of [SamWSoftware's "Fake Hemingway"](https://github.com/SamWSoftware/Projects/tree/master/hemingway), rebuilt with:

- A single editable surface (the original required clicking "Test Me" against a separate read-only output; here the highlighted text itself stays editable, so you can keep typing or paste in a draft and revise it in place).
- Fixed highlighting bugs from the original, where later analysis passes re-scanned text that already contained injected HTML from earlier passes, corrupting matches. Analysis now runs once per sentence against plain text to produce a flat list of character-offset ranges, which are rendered in a single pass.
- User text is HTML-escaped before it's ever inserted into the page, closing an XSS hole in the original (which inserted raw user input via `innerHTML`).
- Broader passive-voice detection: the original only caught "to be" + a word ending in "-ed"; this adds a list of common irregular past participles (e.g. "was eaten", "is written").
- Sentence splitting also recognizes `!` and `?` as sentence endings, not just `.`.
- A responsive flex layout (the original used `position: absolute`, which didn't adapt to smaller screens).

## Opening it

There are three ways, depending on what you want:

**1. As a hosted website.** Pushing to `main` publishes the site via GitHub Pages at
`https://<your-username>.github.io/Hemingway-without-ai/`. This needs to be switched on
once: **Settings → Pages → Build and deployment → Source: GitHub Actions**.

**2. As a single file, no server.** Download **`hemingway.html`** and double-click it.
Everything — styles, word lists, all the logic — is inlined into that one file, so it
runs offline with no install and nothing to serve.

**3. From source, with a local server.**

```sh
npm start
```

> Opening `index.html` from your filesystem (a `file://` URL) will **not** work — it
> loads its JavaScript as ES modules, and browsers block those over `file://` as
> cross-origin requests, leaving you with a blank page. Use `npm start`, or use
> `hemingway.html`, which exists precisely for that case.

## Building

`hemingway.html` is generated from the source files and committed to the repo so it can
be downloaded and opened directly. After changing anything under `js/`, `css/`, or
`index.html`, regenerate it:

```sh
npm run build
```

CI fails the build if the committed copy is stale, so the single-file version can't
silently drift from the source.

## Project structure

```
index.html         Page shell
css/styles.css     Layout + theme (light/dark) styles
js/wordLists.js    Adverb-exception, complex-word, and qualifier-phrase lists
js/analyze.js      Pure analysis functions (no DOM) — sentence splitting, grade-level
                     scoring, and highlight-range detection
js/render.js       Turns analysis output into escaped, safe HTML
js/app.js          DOM wiring: live re-analysis, caret preservation, paste handling,
                     stats panel, dark mode
build.js           Inlines the above into the standalone hemingway.html
hemingway.html     Generated — do not edit by hand
```
