// Turns analyze.js output into HTML. All raw text is escaped before it is
// ever placed into a string that gets assigned to innerHTML, so arbitrary
// user input (including "<script>...</script>") can never execute.

const ESCAPE_MAP = { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" };

export function escapeHtml(str) {
  return str.replace(/[&<>"']/g, ch => ESCAPE_MAP[ch]);
}

const LABELS = {
  adverb: "Adverb — consider cutting it",
  qualifier: "Qualifier/hedge — weakens the sentence",
  passive: "Passive voice",
  complex: null, // tooltip supplied per-range with suggested alternatives
  hardSentence: "Hard to read",
  veryHardSentence: "Very hard to read"
};

function tooltipFor(range) {
  if (range.tooltip) return range.tooltip;
  return LABELS[range.type] || null;
}

// Renders one analyzed sentence (see analyzeSentence in analyze.js) to an
// HTML string: word/phrase-level ranges are applied in a single left-to-right
// pass over the escaped text, then (if the sentence is hard/very hard to
// read) the whole thing is wrapped in an outer difficulty span, so sentence-
// level and word-level highlights nest instead of competing for the same span.
export function renderSentence(analyzed) {
  const { text, ranges, difficulty } = analyzed;
  let html = "";
  let cursor = 0;

  for (const range of ranges) {
    if (range.start > cursor) {
      html += escapeHtml(text.slice(cursor, range.start));
    }
    const segment = escapeHtml(text.slice(range.start, range.end));
    const tooltip = tooltipFor(range);
    const titleAttr = tooltip ? ` title="${escapeHtml(tooltip)}"` : "";
    html += `<span class="${range.type}"${titleAttr}>${segment}</span>`;
    cursor = Math.max(cursor, range.end);
  }
  if (cursor < text.length) {
    html += escapeHtml(text.slice(cursor));
  }

  if (difficulty) {
    const titleAttr = ` title="${escapeHtml(LABELS[difficulty])}"`;
    html = `<span class="${difficulty}"${titleAttr}>${html}</span>`;
  }

  return html;
}

// Renders the full analyzed document (see analyzeText) to an HTML string
// suitable for the editor's contenteditable innerHTML.
export function renderDocument(analysis) {
  return analysis.paragraphs
    .map(paragraph => {
      const sentenceHtml = paragraph.sentences.map(renderSentence).join(" ");
      return `<div class="paragraph">${sentenceHtml || "<br>"}</div>`;
    })
    .join("");
}
