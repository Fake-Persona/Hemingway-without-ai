// Core readability analysis. Pure functions, no DOM access, so this module
// can be tested and reasoned about independently of rendering.
//
// Design note: the original app repeatedly re-scanned strings that already
// contained injected `<span>` markup from earlier passes (adverbs, then
// complex words, then passive voice, then qualifiers), which could corrupt
// matches and double-count. Here, each sentence is analyzed once against
// its *plain* text to produce a flat list of non-overlapping highlight
// ranges (with character offsets), which the renderer then applies in a
// single pass. This also means user text is never interpolated into HTML
// before being escaped, which closes the XSS hole in the original.

import {
  NON_ADVERB_LY_WORDS,
  IRREGULAR_PAST_PARTICIPLES,
  QUALIFYING_PHRASES,
  COMPLEX_WORDS
} from "./wordLists.js";

const BE_VERBS = new Set(["is", "are", "was", "were", "be", "been", "being"]);

// Word/phrase-level highlight types, in priority order for overlap
// resolution (earlier wins). Sentence-level difficulty ("hardSentence" /
// "veryHardSentence") is handled separately as an outer wrapper so it can
// nest around these instead of competing with them for the same span.
export const HIGHLIGHT_PRIORITY = ["passive", "complex", "qualifier", "adverb"];

function escapeRegExp(str) {
  return str.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

// Splits a paragraph into sentences, keeping the terminating punctuation.
// Handles '.', '!' and '?' (the original only handled '.'), and only treats
// a run of terminator characters as a sentence boundary when it's followed
// by whitespace or the end of the string — so "example.com" or "window."
// followed immediately by more non-space text isn't mistaken for a sentence
// end. Every character of the input ends up in exactly one output sentence;
// nothing is silently dropped.
export function splitIntoSentences(paragraph) {
  const trimmed = paragraph.trim();
  if (trimmed.length === 0) return [];

  const sentences = [];
  let start = 0;
  let i = 0;
  while (i < trimmed.length) {
    if (/[.!?]/.test(trimmed[i])) {
      let end = i;
      while (end + 1 < trimmed.length && /[.!?]/.test(trimmed[end + 1])) end++;
      const nextChar = trimmed[end + 1];
      if (nextChar === undefined || /\s/.test(nextChar)) {
        sentences.push(trimmed.slice(start, end + 1).trim());
        start = end + 1;
      }
      i = end + 1;
    } else {
      i++;
    }
  }
  if (start < trimmed.length) {
    sentences.push(trimmed.slice(start).trim());
  }

  return sentences.filter(s => s.length > 0);
}

function countWordsAndLetters(sentence) {
  const clean = sentence.replace(/[^a-z0-9. ]/gi, "");
  const words = clean.split(/\s+/).filter(Boolean);
  const letters = words.join("").length;
  return { wordCount: words.length, letterCount: letters };
}

// Simplified Flesch-Kincaid-style grade level, matching the original's
// letters-per-word approximation (no syllable counting).
export function calculateGradeLevel(letterCount, wordCount, sentenceCount) {
  if (wordCount === 0 || sentenceCount === 0) return 0;
  const grade = Math.round(
    4.71 * (letterCount / wordCount) + (0.5 * wordCount) / sentenceCount - 21.43
  );
  return grade <= 0 ? 0 : grade;
}

export function gradeLevelLabel(grade) {
  if (grade <= 8) return "Great — easy to read";
  if (grade <= 12) return "Good";
  if (grade <= 16) return "Fairly difficult";
  return "Very difficult";
}

function addRange(ranges, start, end, type, tooltip) {
  if (end <= start) return;
  ranges.push({ start, end, type, tooltip });
}

// Finds every non-overlapping occurrence of `phrase` (word-boundary aware)
// in `lowerText` and pushes a highlight range for each.
function findPhraseRanges(lowerText, phrase, type, ranges, tooltip) {
  const pattern = new RegExp(`\\b${escapeRegExp(phrase)}\\b`, "gi");
  let match;
  while ((match = pattern.exec(lowerText)) !== null) {
    addRange(ranges, match.index, match.index + match[0].length, type, tooltip);
    if (match[0].length === 0) pattern.lastIndex++;
  }
}

function findAdverbRanges(sentence, ranges) {
  const pattern = /[A-Za-z']+/g;
  let match;
  while ((match = pattern.exec(sentence)) !== null) {
    const word = match[0];
    const lower = word.toLowerCase();
    if (lower.endsWith("ly") && !NON_ADVERB_LY_WORDS.has(lower)) {
      addRange(ranges, match.index, match.index + word.length, "adverb", null);
    }
  }
}

function findPassiveRanges(sentence, ranges) {
  const pattern = /[A-Za-z']+/g;
  const tokens = [];
  let match;
  while ((match = pattern.exec(sentence)) !== null) {
    tokens.push({ word: match[0], start: match.index, end: match.index + match[0].length });
  }
  for (let i = 0; i < tokens.length - 1; i++) {
    const first = tokens[i].word.toLowerCase();
    const second = tokens[i + 1].word.toLowerCase();
    if (!BE_VERBS.has(first)) continue;
    const isPastParticiple = second.endsWith("ed") || IRREGULAR_PAST_PARTICIPLES.has(second);
    if (isPastParticiple) {
      addRange(ranges, tokens[i].start, tokens[i + 1].end, "passive", null);
    }
  }
}

function findComplexRanges(sentence, ranges) {
  const lower = sentence.toLowerCase();
  for (const phrase of Object.keys(COMPLEX_WORDS)) {
    const alternatives = COMPLEX_WORDS[phrase];
    findPhraseRanges(lower, phrase, "complex", ranges, `Try: ${alternatives.join(", ")}`);
  }
}

function findQualifierRanges(sentence, ranges) {
  const lower = sentence.toLowerCase();
  for (const phrase of QUALIFYING_PHRASES) {
    findPhraseRanges(lower, phrase, "qualifier", ranges, null);
  }
}

// Removes lower-priority ranges that overlap a higher-priority one, and
// sorts what's left by start position so the renderer can walk them in order.
function resolveOverlaps(ranges) {
  const priorityOf = type => HIGHLIGHT_PRIORITY.indexOf(type);
  const sorted = [...ranges].sort((a, b) => priorityOf(a.type) - priorityOf(b.type));
  const accepted = [];
  for (const range of sorted) {
    const overlaps = accepted.some(a => range.start < a.end && range.end > a.start);
    if (!overlaps) accepted.push(range);
  }
  return accepted.sort((a, b) => a.start - b.start);
}

// Analyzes a single sentence and returns its plain text plus a list of
// non-overlapping highlight ranges (character offsets into that text).
export function analyzeSentence(sentence) {
  const { wordCount, letterCount } = countWordsAndLetters(sentence);
  const ranges = [];

  findAdverbRanges(sentence, ranges);
  findComplexRanges(sentence, ranges);
  findPassiveRanges(sentence, ranges);
  findQualifierRanges(sentence, ranges);

  const grade = calculateGradeLevel(letterCount, wordCount, 1);
  let difficulty = null;
  if (wordCount >= 14) {
    if (grade >= 14) difficulty = "veryHardSentence";
    else if (grade >= 10) difficulty = "hardSentence";
  }

  return {
    text: sentence,
    wordCount,
    letterCount,
    difficulty,
    ranges: resolveOverlaps(ranges)
  };
}

// Analyzes the full document text and returns per-paragraph sentence
// breakdowns plus aggregate counts and an overall grade level.
export function analyzeText(text) {
  const paragraphs = text.split("\n");
  const totals = {
    paragraphs: paragraphs.length,
    sentences: 0,
    words: 0,
    letters: 0,
    hardSentences: 0,
    veryHardSentences: 0,
    adverbs: 0,
    passiveVoice: 0,
    complex: 0,
    qualifiers: 0
  };

  const analyzedParagraphs = paragraphs.map(paragraph => {
    const sentences = splitIntoSentences(paragraph).map(sentence => {
      const analyzed = analyzeSentence(sentence);
      totals.sentences += 1;
      totals.words += analyzed.wordCount;
      totals.letters += analyzed.letterCount;
      if (analyzed.difficulty === "hardSentence") totals.hardSentences += 1;
      if (analyzed.difficulty === "veryHardSentence") totals.veryHardSentences += 1;
      for (const range of analyzed.ranges) {
        if (range.type === "adverb") totals.adverbs += 1;
        else if (range.type === "passive") totals.passiveVoice += 1;
        else if (range.type === "complex") totals.complex += 1;
        else if (range.type === "qualifier") totals.qualifiers += 1;
      }
      return analyzed;
    });
    return { raw: paragraph, sentences };
  });

  const overallGrade = calculateGradeLevel(totals.letters, totals.words, totals.sentences);

  return { paragraphs: analyzedParagraphs, totals, overallGrade };
}
