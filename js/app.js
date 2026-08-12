// Wires the contenteditable editor up to the analysis/render pipeline,
// keeps the caret in place across re-renders, normalizes pasted content to
// plain text, and drives the stats panel + dark mode toggle.

import { analyzeText, gradeLevelLabel, flattenHighlights } from "./analyze.js";
import { renderDocument } from "./render.js";

const DEBOUNCE_MS = 250;
const THEME_STORAGE_KEY = "hemingway-theme";

const DEFAULT_TEXT = `The app highlights lengthy, complex sentences and common errors; if you see a yellow sentence, shorten or split it. If you see a red highlight, your sentence is so dense and complicated that your readers will get lost trying to follow its meandering, splitting logic - try editing this sentence to remove the red.
You can utilize a shorter word in place of a purple one. Hover over them for hints.
Adverbs and weakening phrases are helpfully shown in blue. Get rid of them and pick words with force, perhaps.
Phrases in green have been marked to show passive voice.
Write something new, or paste in something you're already working on, then keep editing right here - the highlights update as you go.`;

const editor = document.getElementById("editor");
const themeToggle = document.getElementById("theme-toggle");
const gradeValueEl = document.getElementById("grade-value");
const gradeLabelEl = document.getElementById("grade-label");

const statEls = {
  adverb: document.getElementById("stat-adverb"),
  passive: document.getElementById("stat-passive"),
  complex: document.getElementById("stat-complex"),
  hardSentence: document.getElementById("stat-hard"),
  veryHardSentence: document.getElementById("stat-very-hard")
};

let debounceTimer = null;

// --- Caret position, expressed as a plain-text character offset from the
// start of the editor, so it survives a full innerHTML replacement. ---

function getCaretOffset(root) {
  const selection = window.getSelection();
  if (!selection || selection.rangeCount === 0) return null;
  const range = selection.getRangeAt(0);
  if (!root.contains(range.startContainer)) return null;
  const preRange = range.cloneRange();
  preRange.selectNodeContents(root);
  preRange.setEnd(range.endContainer, range.endOffset);
  return preRange.toString().length;
}

function setCaretOffset(root, offset) {
  if (offset === null) return;
  const selection = window.getSelection();
  const range = document.createRange();
  let remaining = offset;
  let targetNode = null;
  let targetOffset = 0;

  (function walk(node) {
    if (targetNode) return;
    if (node.nodeType === Node.TEXT_NODE) {
      const len = node.textContent.length;
      if (remaining <= len) {
        targetNode = node;
        targetOffset = remaining;
      } else {
        remaining -= len;
      }
      return;
    }
    for (const child of node.childNodes) {
      walk(child);
      if (targetNode) return;
    }
  })(root);

  if (targetNode) {
    range.setStart(targetNode, targetOffset);
  } else {
    range.selectNodeContents(root);
    range.collapse(false);
  }
  range.collapse(true);
  selection.removeAllRanges();
  selection.addRange(range);
}

// --- Analysis + render pipeline ---

function updateStats(totals, overallGrade) {
  const targetAdverbs = Math.max(1, Math.round(totals.sentences / 3));
  const targetPassive = Math.max(1, Math.round(totals.sentences / 5));

  statEls.adverb.innerHTML =
    `<strong>${totals.adverbs}</strong> adverb${totals.adverbs === 1 ? "" : "s"}. ` +
    `Aim for ${targetAdverbs} or fewer.`;
  statEls.passive.innerHTML =
    `Passive voice used <strong>${totals.passiveVoice}</strong> time${totals.passiveVoice === 1 ? "" : "s"}. ` +
    `Aim for ${targetPassive} or fewer.`;
  statEls.complex.innerHTML =
    `<strong>${totals.complex}</strong> phrase${totals.complex === 1 ? "" : "s"} could be simplified.`;
  statEls.hardSentence.innerHTML =
    `<strong>${totals.hardSentences}</strong> of ${totals.sentences} sentence${totals.sentences === 1 ? "" : "s"} ` +
    `${totals.sentences === 1 ? "is" : "are"} hard to read.`;
  statEls.veryHardSentence.innerHTML =
    `<strong>${totals.veryHardSentences}</strong> of ${totals.sentences} sentence${totals.sentences === 1 ? "" : "s"} ` +
    `${totals.sentences === 1 ? "is" : "are"} very hard to read.`;

  if (totals.words === 0) {
    gradeValueEl.textContent = "–";
    gradeLabelEl.textContent = "Start writing to see your grade level";
  } else {
    gradeValueEl.textContent = String(overallGrade);
    gradeLabelEl.textContent = gradeLevelLabel(overallGrade);
  }
}

// Reads the editor's text as the exact inverse of what renderDocument writes:
// one line per top-level block, joined by "\n".
//
// This deliberately avoids `innerText`, which reports a *rendered* view rather
// than our source. renderDocument emits `<div class="paragraph"><br></div>` to
// keep a blank line visible, and innerText reads that back as TWO newlines —
// one for the <br>, one for the block boundary. Feeding that back in produced
// two blank paragraphs, then four, then eight: whitespace doubling on every
// keystroke. textContent has no such rendering fiction, so the round-trip is
// stable.
//
// A trailing bare <br> is skipped: browsers park one at the end of a
// contenteditable purely to give the caret somewhere to sit, and it is not a
// line the user typed.
function readEditorText(editor) {
  const blocks = [];
  const children = [...editor.childNodes];

  children.forEach((node, index) => {
    if (node.nodeType === Node.TEXT_NODE) {
      blocks.push(node.data);
      return;
    }
    if (node.nodeType !== Node.ELEMENT_NODE) return;
    if (node.tagName === "BR") {
      const isTrailingCaretHolder = index === children.length - 1;
      if (!isTrailingCaretHolder) blocks.push("");
      return;
    }
    blocks.push(node.textContent);
  });

  return blocks.join("\n");
}

function runAnalysis() {
  const hasFocus = document.activeElement === editor;
  const caretOffset = hasFocus ? getCaretOffset(editor) : null;

  const text = readEditorText(editor);
  const analysis = analyzeText(text);

  editor.innerHTML = renderDocument(analysis);

  if (hasFocus) {
    setCaretOffset(editor, caretOffset);
  }

  updateStats(analysis.totals, analysis.overallGrade);
}

function scheduleAnalysis() {
  if (debounceTimer) clearTimeout(debounceTimer);
  debounceTimer = setTimeout(runAnalysis, DEBOUNCE_MS);
}

editor.addEventListener("input", scheduleAnalysis);

// Force paste to insert plain text only, so formatting/markup from the
// clipboard (e.g. copying from a rich text editor or a webpage) never ends
// up in the editor's DOM.
function insertPlainTextAtCaret(text) {
  const selection = window.getSelection();
  if (!selection.rangeCount) return;
  const range = selection.getRangeAt(0);
  range.deleteContents();
  const textNode = document.createTextNode(text);
  range.insertNode(textNode);
  range.setStartAfter(textNode);
  range.setEndAfter(textNode);
  selection.removeAllRanges();
  selection.addRange(range);
}

editor.addEventListener("paste", event => {
  event.preventDefault();
  const clipboardData = event.clipboardData || window.clipboardData;
  const text = clipboardData.getData("text/plain");
  if (document.queryCommandSupported && document.queryCommandSupported("insertText")) {
    document.execCommand("insertText", false, text);
  } else {
    insertPlainTextAtCaret(text);
  }
  scheduleAnalysis();
});

function loadInitialText(text) {
  const analysis = analyzeText(text);
  editor.innerHTML = renderDocument(analysis);
  updateStats(analysis.totals, analysis.overallGrade);
}

loadInitialText(DEFAULT_TEXT);

// --- Embedding bridge ---
//
// A stable, minimal surface for a host that embeds this page in a web view
// (the Android app passes in the text you selected in another app, and reads
// back whatever you edited). Keeping it to two functions means the host never
// reaches into the DOM or internals, so this file stays free to change.
window.hemingway = {
  setText(text) {
    loadInitialText(typeof text === "string" ? text : "");
  },
  getText() {
    return readEditorText(editor);
  },
  // Stats only, without touching the DOM. The Android floating panel reports on
  // text you are typing in a different app, so it needs the numbers without
  // rendering an editor it will never show. Returns a JSON string because that
  // is what survives the web view bridge intact.
  // Everything the Android panel needs in one call: the score, the totals, and
  // each individual issue with the offending words and where they sit.
  //
  // The offsets matter as much as the text. Tapping an issue there selects that
  // exact range in whatever app you are writing in, so the app scrolls to it and
  // marks it with its own selection — pointing at the problem without this
  // having to know anything about where it is on screen.
  analyze(text) {
    const source = typeof text === "string" ? text : "";
    const analysis = analyzeText(source);
    return JSON.stringify({
      grade: analysis.overallGrade,
      label: gradeLevelLabel(analysis.overallGrade),
      totals: analysis.totals,
      issues: flattenHighlights(analysis).map(highlight => ({
        start: highlight.start,
        end: highlight.end,
        type: highlight.type,
        text: source.slice(highlight.start, highlight.end)
      }))
    });
  }
};

// --- Dark mode ---

function applyTheme(theme) {
  document.documentElement.dataset.theme = theme;
  themeToggle.checked = theme === "dark";
}

function initTheme() {
  const stored = localStorage.getItem(THEME_STORAGE_KEY);
  const prefersDark = window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches;
  applyTheme(stored || (prefersDark ? "dark" : "light"));
}

themeToggle.addEventListener("change", () => {
  const next = themeToggle.checked ? "dark" : "light";
  applyTheme(next);
  localStorage.setItem(THEME_STORAGE_KEY, next);
});

initTheme();
