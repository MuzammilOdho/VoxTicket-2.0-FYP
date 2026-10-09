package com.voxticket.service;

import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Generates short, VARCHAR(30)-safe business reference numbers at runtime.
 * Centralizing this exists specifically so a repeat of the
 * "generated a 36-character UUID into a 30-char column" bug from the Phase 1
 * test fixtures can't happen again in real service code - every caller gets
 * an 8-character suffix by construction, not by remembering to truncate.
 */
@Component
public class ReferenceNumberGenerator {

    public String returnNumber() {
        return "RTN-" + shortId();
    }

    public String refundNumber() {
        return "RFN-" + shortId();
    }

    public String claimNumber() {
        return "CLM-" + shortId();
    }

    public String ticketNumber() {
        return "TCK-" + shortId();
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }
}