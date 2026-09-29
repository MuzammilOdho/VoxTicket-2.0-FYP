package com.voxticket.verification;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Pass 2D-A: deterministic parser for user turns received while a procedure
 * is {@code AWAITING_VERIFICATION}.
 *
 * <p>Pure by construction: no DB, no model, no provider, no session mutation,
 * and never any logging of raw input (the caller logs only boolean presence
 * flags). It is only ever consulted when a verification is actually pending -
 * six-digit numbers elsewhere in the conversation are never interpreted here.
 *
 * <p>OTP extraction rules:
 * <ul>
 *   <li>Standalone six ASCII digits, or two adjacent three-digit groups
 *       ("482 916"), or the same forms written with any Unicode decimal
 *       digits (e.g. Arabic-Indic), normalized deterministically via
 *       {@link Character#digit(char, int)}.</li>
 *   <li>Six digits embedded in identifiers are NOT candidates:
 *       {@code ORD-123456}, {@code TCK-123456}, {@code ABC123456} and
 *       seven-digit (or longer) runs are rejected. The regression that
 *       {@code ORD-123456} is never a verification code is preserved.</li>
 *   <li>Several <em>distinct</em> candidates ("482916 or 123456") set
 *       {@code multipleCandidates} - the runtime must ask for one code and
 *       submit nothing.</li>
 * </ul>
 *
 * <p>Resend rule: existing English resend forms are recognized. When a turn
 * carries both an OTP candidate and resend language, the explicit candidate
 * wins ("resend 482916" submits the code); resend is only reported when no
 * candidate is present.
 *
 * <p>Residual text: the OTP/resend spans are removed and harmless connector
 * noise ("and", "also", intro phrases like "my code is") is cleaned, so the
 * agent receives useful language. No grammatical rewriting is attempted; if
 * nothing substantive remains the residual is {@code null}.
 */
@Component
public class SensitiveTurnParser {

    /** Stable placeholder stored in history/audit/model input instead of OTP plaintext. */
    public static final String REDACTED_OTP_PLACEHOLDER = "[verification code provided]";

    /** Marks OTP/resend removal points while residual text is cleaned. */
    private static final char REMOVAL_MARK = '\u0000';

    private static final Pattern RESEND_KEYWORDS = Pattern.compile(
            "\\b(resend|send (it )?again|new code|didn'?t (get|receive)|haven'?t (got|received))\\b", Pattern.CASE_INSENSITIVE);

    /** Leading connectors stripped from residual text after span removal. */
    private static final Set<String> LEADING_CONNECTORS = Set.of("and", "also", "then", "plus", "please", "or");

    /** Intro phrases stripped when they are all that precedes the residual content. */
    private static final List<String> LEADING_INTROS = List.of("my code is", "the code is", "code is", "the code", "code");

    public SensitiveTurn parse(String message) {
        String text = message == null ? "" : message;
        // 1:1 index-preserving normalization of every Unicode decimal digit to ASCII.
        String ascii = normalizeDigits(text);

        List<int[]> candidateSpans = findCandidateSpans(ascii);
        // Canonicalize each candidate to six ASCII digits BEFORE the
        // distinctness comparison: "482 916" and "482916" are the same code,
        // and repeated identical codes are not ambiguity. Every detected
        // span is kept so redaction and residual removal cover all of them.
        Map<String, List<int[]>> distinct = new LinkedHashMap<>();
        for (int[] span : candidateSpans) {
            distinct.computeIfAbsent(canonicalDigits(ascii, span), key -> new ArrayList<>()).add(span);
        }
        List<int[]> allSpans = new ArrayList<>();
        for (List<int[]> spans : distinct.values()) {
            allSpans.addAll(spans);
        }
        // Pass 2D cleanup: grouping by canonical code destroys positional
        // order ("111111 222222 111111" flattens out of order). Every span
        // list handed to redactSpans/extractResidual must be sorted by start
        // offset ascending - both use cursor-based substring logic.
        allSpans.sort((a, b) -> Integer.compare(a[0], b[0]));

        boolean multiple = distinct.size() > 1;
        String candidate = null;
        if (!multiple && !distinct.isEmpty()) {
            candidate = distinct.keySet().iterator().next();
        }

        Matcher resendMatcher = RESEND_KEYWORDS.matcher(text);
        boolean resendLanguage = resendMatcher.find();
        // Explicit OTP candidate wins over resend language; resend only when no candidate.
        boolean resendRequested = !multiple && candidate == null && resendLanguage;

        String redacted = redactSpans(text, allSpans);
        String residual = extractResidual(text, candidateSpansForRemoval(allSpans, resendRequested ? resendMatcher : null));
        return new SensitiveTurn(candidate, multiple, resendRequested, residual, redacted);
    }

    /**
     * Pass 2D cleanup: canonicalizes a candidate span to exactly six ASCII
     * digits, filtering every {@link Character#isWhitespace(char)} separator
     * ("482 916", "482\t916", ...). Deterministic character filtering on the
     * span only - never a broad regex over surrounding text, so identifiers
     * elsewhere in the message cannot be altered.
     */
    private static String canonicalDigits(String ascii, int[] span) {
        StringBuilder canonical = new StringBuilder(span[1] - span[0]);
        for (int i = span[0]; i < span[1]; i++) {
            char c = ascii.charAt(i);
            if (!Character.isWhitespace(c)) {
                canonical.append(c);
            }
        }
        return canonical.toString();
    }

    /**
     * Converts every Unicode decimal digit to its ASCII equivalent, leaving
     * all other characters untouched so character indexes stay aligned with
     * the original text.
     */
    static String normalizeDigits(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int digit = Character.digit(c, 10);
            sb.append(digit >= 0 ? (char) ('0' + digit) : c);
        }
        return sb.toString();
    }

    /**
     * Finds spans of plausible OTP candidates: standalone six-digit tokens or
     * adjacent three-digit token pairs, where a token is whitespace-delimited
     * with only surrounding punctuation stripped. Tokens containing letters or
     * hyphens ("ORD-123456", "ABC123456") and runs of seven or more digits
     * never qualify.
     */
    private List<int[]> findCandidateSpans(String ascii) {
        List<int[]> spans = new ArrayList<>();
        List<int[]> tokens = tokenSpans(ascii);
        for (int i = 0; i < tokens.size(); i++) {
            int[] token = tokens.get(i);
            int[] core = strippedCore(ascii, token);
            if (core == null) {
                continue;
            }
            String stripped = ascii.substring(core[0], core[1]);
            if (stripped.length() == 6 && allDigits(stripped)) {
                spans.add(core);
                continue;
            }
            if (stripped.length() == 3 && allDigits(stripped) && i + 1 < tokens.size()) {
                int[] nextCore = strippedCore(ascii, tokens.get(i + 1));
                if (nextCore != null) {
                    String nextStripped = ascii.substring(nextCore[0], nextCore[1]);
                    if (nextStripped.length() == 3 && allDigits(nextStripped)) {
                        spans.add(new int[]{core[0], nextCore[1]});
                        i++;
                    }
                }
            }
        }
        return spans;
    }

    /**
     * The span of a token with surrounding punctuation removed, or
     * {@code null} when nothing remains. The span covers only the digit
     * core, so "482916," redacts to "[verification code provided]," and the
     * candidate key is pure digits.
     */
    private int[] strippedCore(String ascii, int[] token) {
        int start = token[0];
        int end = token[1];
        while (start < end && isEdgePunctuation(ascii.charAt(start))) {
            start++;
        }
        while (end > start && isEdgePunctuation(ascii.charAt(end - 1))) {
            end--;
        }
        return start >= end ? null : new int[]{start, end};
    }

    /** Whitespace-delimited token spans (start inclusive, end exclusive). */
    private List<int[]> tokenSpans(String text) {
        List<int[]> tokens = new ArrayList<>();
        int start = -1;
        for (int i = 0; i <= text.length(); i++) {
            boolean boundary = i == text.length() || Character.isWhitespace(text.charAt(i));
            if (!boundary && start < 0) {
                start = i;
            } else if (boundary && start >= 0) {
                tokens.add(new int[]{start, i});
                start = -1;
            }
        }
        return tokens;
    }

    private boolean isEdgePunctuation(char c) {
        return ".,;:!?()[]{}\"'«»".indexOf(c) >= 0;
    }

    private boolean allDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private String redactSpans(String text, List<int[]> spans) {
        if (spans.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        int cursor = 0;
        for (int[] span : spans) {
            sb.append(text, cursor, span[0]);
            sb.append(REDACTED_OTP_PLACEHOLDER);
            cursor = span[1];
        }
        sb.append(text.substring(cursor));
        return sb.toString();
    }

    private List<int[]> candidateSpansForRemoval(List<int[]> otpSpans, Matcher resendMatcher) {
        List<int[]> spans = new ArrayList<>(otpSpans);
        if (resendMatcher != null) {
            // Resend turns have no OTP candidate spans; only the resend phrase is removed.
            spans.add(new int[]{resendMatcher.start(), resendMatcher.end()});
        }
        spans.sort((a, b) -> Integer.compare(a[0], b[0]));
        return spans;
    }

    /**
     * Removes the given spans and cleans connector noise so the residual is
     * useful agent input. Returns {@code null} when nothing was removed or
     * nothing substantive remains.
     */
    private String extractResidual(String text, List<int[]> spans) {
        if (spans.isEmpty()) {
            return null;
        }
        // Mark removal points so only punctuation directly orphaned by the
        // removal is cleaned - hyphens inside identifiers such as ORD-10002
        // are never touched.
        StringBuilder sb = new StringBuilder();
        int cursor = 0;
        for (int[] span : spans) {
            sb.append(text, cursor, span[0]);
            sb.append(REMOVAL_MARK);
            cursor = span[1];
        }
        sb.append(text.substring(cursor));
        // NUL is not a regex metacharacter, so it needs no quoting.
        String marked = sb.toString()
                .replaceAll("\u0000\\s*[,;:\\-\u2013\u2014]", "\u0000")
                .replaceAll("[,;:\\-\u2013\u2014]\\s*\u0000", "\u0000");
        String cleaned = collapseWhitespace(marked.replace(REMOVAL_MARK, ' ')).trim();
        cleaned = stripLeadingNoise(cleaned);
        cleaned = stripTrailingPunctuation(cleaned);
        if (cleaned.isEmpty() || isIntroOnly(cleaned) || containsNoLettersOrDigits(cleaned)) {
            return null;
        }
        return cleaned;
    }

    private String collapseWhitespace(String text) {
        return text.replaceAll("\\s+", " ");
    }

    private String stripLeadingNoise(String text) {
        String current = text;
        boolean changed;
        do {
            changed = false;
            String lower = current.toLowerCase(Locale.ROOT);
            for (String connector : LEADING_CONNECTORS) {
                if (lower.equals(connector) || lower.startsWith(connector + " ")) {
                    current = current.substring(connector.length()).trim();
                    changed = true;
                    break;
                }
            }
            if (!changed) {
                for (String intro : LEADING_INTROS) {
                    if (lower.equals(intro) || lower.startsWith(intro + " ")) {
                        current = current.substring(intro.length()).trim();
                        changed = true;
                        break;
                    }
                }
            }
        } while (changed && !current.isEmpty());
        return current;
    }

    private String stripTrailingPunctuation(String text) {
        int end = text.length();
        while (end > 0 && ",;:".indexOf(text.charAt(end - 1)) >= 0) {
            end--;
        }
        return text.substring(0, end).trim();
    }

    private boolean isIntroOnly(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return LEADING_INTROS.contains(lower);
    }

    private boolean containsNoLettersOrDigits(String text) {
        return text.codePoints().noneMatch(c -> Character.isLetterOrDigit(c));
    }
}
