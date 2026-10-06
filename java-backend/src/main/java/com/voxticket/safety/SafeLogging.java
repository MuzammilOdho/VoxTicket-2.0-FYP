package com.voxticket.safety;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * So every log statement that needs to correlate a piece of customer-
 * supplied text without ever printing it uses the same approach. Not
 * cryptographic - just a short, stable fingerprint for grep/correlation
 * across log lines.
 */
public final class SafeLogging {

    private SafeLogging() {
    }

    public static String hash(String text) {
        if (text == null) {
            return "none";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            return "unavailable";
        }
    }
}