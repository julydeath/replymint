//! Platform-independent screen-context types shared by the per-OS readers
//! (ax.rs on macOS, uia.rs on Windows) and their consumers (reply.rs, lib.rs).
//!
//! Caps mirror android/ScreenReader: last 60 lines, 1500-node budget, deduped.

use std::collections::HashSet;

pub const MAX_LINES: usize = 60;
pub const NODE_BUDGET: usize = 1500;
const MAX_KEYWORDS: usize = 50;
const MAX_KEYWORD_CHARS: usize = 60;

/// What the focused window looked like when dictation started.
#[derive(Debug, Clone)]
pub struct ScreenContext {
    pub app_name: String,
    pub window_title: String,
    /// Visible text lines, top-to-bottom, capped to the last [`MAX_LINES`].
    pub lines: Vec<String>,
    /// Current content of the focused text field, if any.
    pub focused_text: Option<String>,
}

/// Screen keywords for STT biasing: capitalized words (and adjacent runs, e.g.
/// full names) from the visible lines. Deliberately the same rules as the
/// Android Keywords extractor — keep them in sync.
pub fn extract_keywords(ctx: &ScreenContext) -> Vec<String> {
    const STOPWORDS: &[&str] = &[
        "The", "This", "That", "These", "Those", "And", "But", "For", "Not", "You", "Your",
        "What", "When", "Where", "Which", "Why", "How", "With", "From", "Have", "Has", "Had",
        "Will", "Would", "Could", "Should", "There", "Here", "They", "Them", "Then", "Than",
        "Are", "Was", "Were", "Yes", "Okay", "Please", "Thanks", "Thank", "Hello", "Just",
        "Also", "About", "Can", "Get", "Let", "New", "Now", "One", "Our", "Out", "See",
    ];
    let is_candidate = |w: &str| {
        w.chars().count() >= 3
            && w.chars().next().is_some_and(|c| c.is_uppercase())
            && w.chars().all(char::is_alphabetic)
            && !STOPWORDS.contains(&w)
    };

    let mut keywords: Vec<String> = Vec::new();
    let mut seen = HashSet::new();
    let mut push = |kw: String| {
        if kw.len() <= MAX_KEYWORD_CHARS && seen.insert(kw.clone()) {
            keywords.push(kw);
        }
    };

    for line in &ctx.lines {
        let line = line
            .trim_start_matches("Me:")
            .trim_start_matches("Them:")
            .trim();
        let words: Vec<&str> = line
            .split_whitespace()
            .map(|w| w.trim_matches(|c: char| !c.is_alphanumeric()))
            .collect();
        let mut run: Vec<&str> = Vec::new();
        for w in words.iter().chain(std::iter::once(&"")) {
            if is_candidate(w) {
                run.push(w);
            } else {
                if run.len() > 1 {
                    push(run.join(" ")); // full names beat single words for biasing
                }
                for single in run.drain(..) {
                    push(single.to_string());
                }
            }
        }
    }
    keywords.truncate(MAX_KEYWORDS);
    keywords
}
