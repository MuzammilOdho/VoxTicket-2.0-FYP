package com.voxticket.conversation;

import com.voxticket.verification.SensitiveTurnParser;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Pass 2C (Task 2): a small, pure, deterministic language resolver for the
 * direct runtime path (OTP / confirmation turns that never reach the LLM).
 *
 * <p>It inspects recent {@code USER} messages on the session, newest first,
 * and the newest message carrying a useful language signal wins. OTP digits,
 * the OTP redaction placeholder, order/reference identifiers, and
 * punctuation-only input carry no language signal, so they never reset a
 * previously established language.
 *
 * <p>No model, no embeddings, no network, no database. Raw user text is never
 * logged.
 */
@Component
public class ConversationLanguageResolver {

    /** Roman-Urdu grammatical/content markers - whole-word matches only. */
    private static final Set<String> ROMAN_URDU_MARKERS = Set.of(
            "mera", "meri", "mere", "mujhe", "mujh", "aap", "ap", "kya", "kyun", "kyu", "kab",
            "kahan", "kidhar", "nahi", "nahin", "haan", "han", "jee", "ji", "kar", "karo", "karein",
            "karen", "karna", "karne", "chahiye", "hua", "hui", "hain", "hai", "tha", "thi",
            "mila", "mili", "aya", "aaya", "wapas", "wapis", "bheja", "bheji",
            "isay", "usay", "iska", "uski", "uske", "iske", "maine", "tum", "tumne",
            "gaya", "gayi", "gaye", "liya", "diya", "dena", "lena", "theek", "sahi", "galat",
            "zaroor", "kabhi", "abhi", "aaj", "kal", "batain", "bataein", "batayein", "bata",
            "khula", "band", "dobara", "karke", "dekh", "dekho",
            "andar", "bahar", "upar", "neeche", "sath",
            "wala", "wali", "wale", "bhi", "sirf",
            "kitna", "kitne", "kaise", "konsa", "konsi", "kyunke");

    /** Ordinary English support/commerce nouns - never Roman-Urdu evidence on their own. */
    private static final Set<String> ENGLISH_COMMERCE_NOUNS = Set.of(
            "order", "orders", "cancel", "cancelled", "canceled", "cancellation",
            "return", "returns", "refund", "refunds", "payment", "payments", "delivery",
            "deliver", "shipment", "ship", "shipped", "ticket", "tickets", "claim", "claims",
            "item", "items", "product", "products", "code", "otp", "number", "track", "tracking",
            "status", "support", "agent", "human", "help", "customer", "service", "review",
            "policy", "charge", "charged", "card", "cash", "amount", "price", "receipt", "invoice");

    /** Short generic confirmations that must not override an established non-English language. */
    private static final Set<String> WEAK_ENGLISH_CONFIRMATIONS = Set.of(
            "yes", "no", "yeah", "yep", "nope", "ok", "okay", "sure", "alright", "all right", "fine", "hmm");

    private static final Pattern IDENTIFIER_PATTERN =
            Pattern.compile("(?i)[A-Z]{2,}[\\-]?(\\d+[A-Z0-9\\-]*)");

    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    /**
     * Resolves the language for a direct deterministic response from the
     * session's recent user history. Falls back to {@link ConversationLanguage#ENGLISH}
     * when nothing useful was said.
     */
    public ConversationLanguage resolve(ConversationSession session) {
        if (session == null) {
            return ConversationLanguage.ENGLISH;
        }
        List<ConversationMessage> messages = session.getRecentMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            ConversationMessage message = messages.get(i);
            if (message.role() != MessageRole.USER) {
                continue;
            }
            Optional<ConversationLanguage> signal = classify(message.text());
            if (signal.isPresent()) {
                return signal.get();
            }
        }
        return ConversationLanguage.ENGLISH;
    }

    /**
     * Classifies a single user message. Empty when it carries no language
     * signal (OTP digits, the OTP redaction placeholder, reference
     * identifiers, punctuation/whitespace, or a weak generic confirmation).
     */
    public Optional<ConversationLanguage> classify(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String trimmed = text.strip();
        // Pass 2D-A: the OTP redaction placeholder is system-inserted, not
        // user language - it must never reset a previously established
        // language. Any remaining text is genuine user language and is
        // classified normally.
        String withoutPlaceholder = trimmed.replace(SensitiveTurnParser.REDACTED_OTP_PLACEHOLDER, " ").strip();
        if (withoutPlaceholder.isEmpty()) {
            return Optional.empty();
        }
        trimmed = withoutPlaceholder;
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (WEAK_ENGLISH_CONFIRMATIONS.contains(lower)) {
            return Optional.empty();
        }

        boolean hasUrduScriptLetters = containsUrduScriptLetters(trimmed);
        String withoutIdentifiers = stripIdentifiers(trimmed);
        boolean hasAnyLetters = containsLetters(withoutIdentifiers);
        if (!hasUrduScriptLetters && !hasAnyLetters) {
            // Pure digits, identifiers, punctuation or whitespace - no language signal.
            return Optional.empty();
        }

        String latinOnly = withoutIdentifiers.replaceAll("[\\u0600-\\u06FF\\u0750-\\u077F\\u08A0-\\u08FF\\uFB50-\\uFDFF\\uFE70-\\uFEFF]", " ");
        boolean hasLatinLetters = latinOnly.chars().anyMatch(Character::isLetter);

        if (hasUrduScriptLetters) {
            // "یہ order cancel کر دیں" - Urdu script plus meaningful Latin content is code-switch.
            return Optional.of(hasLatinLetters ? ConversationLanguage.CODE_SWITCH : ConversationLanguage.URDU);
        }

        if (containsDevanagariLetters(trimmed)) {
            // AssemblyAI detects spoken Urdu/Hindi (Hindustani) as "hi" and
            // transcribes it in Devanagari script. VoxTicket serves it as
            // Urdu: same spoken language, Urdu voice on the way out. Without
            // this, Devanagari input fell through to ENGLISH below.
            return Optional.of(ConversationLanguage.URDU);
        }

        Set<String> tokens = tokenize(lower);
        boolean hasMarkers = tokens.stream().anyMatch(ROMAN_URDU_MARKERS::contains);
        if (!hasMarkers) {
            return Optional.of(ConversationLanguage.ENGLISH);
        }
        boolean hasEnglishContent = tokens.stream()
                .anyMatch(t -> t.length() >= 2
                        && !ROMAN_URDU_MARKERS.contains(t)
                        && !ENGLISH_COMMERCE_NOUNS.contains(t)
                        && !WEAK_ENGLISH_CONFIRMATIONS.contains(t)
                        && t.chars().anyMatch(Character::isLetter));
        return Optional.of(hasEnglishContent ? ConversationLanguage.CODE_SWITCH : ConversationLanguage.ROMAN_URDU);
    }

    private boolean containsUrduScriptLetters(String text) {
        return text.codePoints().anyMatch(cp -> isArabicScriptBlock(cp) && Character.isLetter(cp));
    }

    private boolean containsDevanagariLetters(String text) {
        return text.codePoints().anyMatch(cp -> isDevanagariBlock(cp) && Character.isLetter(cp));
    }

    private boolean isDevanagariBlock(int cp) {
        return cp >= 0x0900 && cp <= 0x097F;
    }

    private boolean isArabicScriptBlock(int cp) {
        return (cp >= 0x0600 && cp <= 0x06FF)
                || (cp >= 0x0750 && cp <= 0x077F)
                || (cp >= 0x08A0 && cp <= 0x08FF)
                || (cp >= 0xFB50 && cp <= 0xFDFF)
                || (cp >= 0xFE70 && cp <= 0xFEFF);
    }

    private boolean containsLetters(String text) {
        return text.codePoints().anyMatch(Character::isLetter);
    }

    private String stripIdentifiers(String text) {
        // Order/return/claim/tracking-style references and bare digit runs carry no signal.
        String withoutIdent = IDENTIFIER_PATTERN.matcher(text).replaceAll(" ");
        return withoutIdent.replaceAll("\\d+", " ");
    }

    private Set<String> tokenize(String lower) {
        Set<String> tokens = new java.util.HashSet<>();
        for (String token : WHITESPACE_PATTERN.split(lower)) {
            String clean = token.replaceAll("^[^\\p{L}]+|[^\\p{L}]+$", "");
            if (!clean.isEmpty()) {
                tokens.add(clean);
            }
        }
        return tokens;
    }
}
