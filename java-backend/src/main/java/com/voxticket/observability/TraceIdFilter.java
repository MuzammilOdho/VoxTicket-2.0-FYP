package com.voxticket.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * P0 (correlation). Extracts or generates the request trace ID before any
 * other filter or controller runs.
 *
 * <p>Reads the {@code traceparent} header (sent by the Python voice worker);
 * when it is missing or malformed a fresh trace ID is generated. The ID is
 * exposed as the {@code traceId} request attribute (controllers copy it into
 * {@code UserTurn} provider metadata) and in SLF4J MDC for the whole request
 * so every log line on the request thread carries it.
 *
 * <p>Best-effort by contract: the filter's own logic never throws, and a
 * missing correlation header never affects request execution. Downstream
 * exceptions still propagate normally - this filter only guarantees its MDC
 * is cleared in a finally block.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /** Request attribute key carrying the resolved trace ID for controllers. */
    public static final String REQUEST_ATTRIBUTE_TRACE_ID = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String traceId = null;
            try {
                traceId = TraceIds.parseTraceParent(request.getHeader(TraceIds.HEADER_TRACEPARENT));
            } catch (Exception ignored) {
                // A hostile header value must never break the request.
            }
            if (traceId == null) {
                traceId = TraceIds.newTraceId();
            }
            request.setAttribute(REQUEST_ATTRIBUTE_TRACE_ID, traceId);
            MDC.put(TraceIds.MDC_TRACE_ID, traceId);
        } catch (Exception ignored) {
            // Absolute last resort: even MDC/attribute setup must not fail the request.
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}
