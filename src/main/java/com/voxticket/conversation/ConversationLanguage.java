package com.voxticket.conversation;

/**
 * The language VoxTicket uses for customer-facing deterministic responses on
 * the direct runtime path (OTP / confirmation turns that never reach the
 * LLM). The raw text is never persisted as a locale - only this stable enum
 * is produced by {@link ConversationLanguageResolver}.
 */
public enum ConversationLanguage {
    /** Concise natural support English. */
    ENGLISH,
    /** Natural Urdu script (اردو). */
    URDU,
    /** Natural Roman Urdu (Latin script). */
    ROMAN_URDU,
    /** Natural English/Roman-Urdu support style. */
    CODE_SWITCH
}
