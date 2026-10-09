package com.voxticket.observability;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P0 (correlation). Best-effort trace/correlation ID utilities.
 *
 * <p>Trace IDs are 32 lowercase hex chars (W3C-compatible) so they can be
 * propagated as a {@code traceparent} header between the Python voice worker
 * and the Java backend, carried in SLF4J MDC on the request thread, and
 * stored on audit/turn-trace rows for admin correlation.
 *
 * <p>Pure static utility - no Spring, no I/O. Every method is null-safe and
 * never throws: missing or malformed correlation data must never affect
 * request execution; callers fall back to generating a fresh ID.
 */
public final class TraceIds {

    /** MDC key for the trace/correlation ID. */
    public static final String MDC_TRACE_ID = "traceId";
    /** MDC key for the conversation session ID. */
    public static final String MDC_SESSION_ID = "sessionId";
    /** MDC key for the turn number within the session. */
    public static final String MDC_TURN = "turnNumber";

    /** HTTP header carrying W3C trace context (Python worker -> Java backend). */
    public static final String HEADER_TRACEPARENT = "traceparent";
    /** {@link com.voxticket.conversation.UserTurn#providerMetadata()} key carrying the trace ID. */
    public static final String METADATA_TRACE_ID = "traceId";

    /**
     * W3C traceparent: {@code version-traceId-parentId-flags}, all lowercase
     * hex. Version {@code ff} is explicitly invalid per the spec.
     */
    private static final Pattern TRACEPARENT_PATTERN =
            Pattern.compile("^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$");

    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("^[0-9a-f]{32}$");

    private TraceIds() {
        // utility
    }

    /** A new random 32-char lowercase hex trace ID. */
    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** A new random 16-char lowercase hex span ID. */
    public static String newSpanId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * Parses a W3C {@code traceparent} header value and returns the 32-hex
     * trace ID it carries, or {@code null} when the input is null, blank, or
     * malformed. Never throws.
     */
    public static String parseTraceParent(String traceparent) {
        try {
            if (traceparent == null || traceparent.isBlank()) {
                return null;
            }
            Matcher m = TRACEPARENT_PATTERN.matcher(traceparent.trim().toLowerCase(Locale.ROOT));
            if (!m.matches()) {
                return null;
            }
            if ("ff".equals(m.group(1))) {
                return null;
            }
            return m.group(2);
        } catch (Exception e) {
            return null;
        }
    }

    /** True when the value is a well-formed 32-char lowercase hex trace ID. Never throws. */
    public static boolean isValidTraceId(String traceId) {
        try {
            return traceId != null && TRACE_ID_PATTERN.matcher(traceId).matches();
        } catch (Exception e) {
            return false;
        }
    }
}
