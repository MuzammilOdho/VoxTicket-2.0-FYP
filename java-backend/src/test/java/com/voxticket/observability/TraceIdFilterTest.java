package com.voxticket.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * P0: the trace filter resolves the trace ID from the {@code traceparent}
 * header (or generates one), exposes it as a request attribute and in MDC
 * for the chain, and always clears MDC afterwards.
 */
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    private record Captured(String attribute, String mdcDuringChain) {}

    private Captured runFilter(String traceparentHeader) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (traceparentHeader != null) {
            request.addHeader(TraceIds.HEADER_TRACEPARENT, traceparentHeader);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> attribute = new AtomicReference<>();
        AtomicReference<String> mdc = new AtomicReference<>();
        FilterChain chain = (req, res) -> {
            attribute.set((String) req.getAttribute(TraceIdFilter.REQUEST_ATTRIBUTE_TRACE_ID));
            mdc.set(MDC.get(TraceIds.MDC_TRACE_ID));
        };
        filter.doFilter(request, response, chain);
        return new Captured(attribute.get(), mdc.get());
    }

    @Test
    void validTraceparentPropagatesItsTraceId() throws Exception {
        Captured captured = runFilter("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");

        assertThat(captured.attribute()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(captured.mdcDuringChain()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    }

    @Test
    void missingHeaderGeneratesATraceId() throws Exception {
        Captured captured = runFilter(null);

        assertThat(captured.attribute()).matches("[0-9a-f]{32}");
        assertThat(captured.mdcDuringChain()).isEqualTo(captured.attribute());
    }

    @Test
    void malformedHeaderGeneratesATraceId() throws Exception {
        Captured captured = runFilter("not-a-traceparent");

        assertThat(captured.attribute()).matches("[0-9a-f]{32}");
        assertThat(captured.mdcDuringChain()).isEqualTo(captured.attribute());
    }

    @Test
    void mdcIsClearedAfterTheChain() throws Exception {
        runFilter(null);

        assertThat(MDC.get(TraceIds.MDC_TRACE_ID)).isNull();
    }

    @Test
    void downstreamExceptionStillPropagatesAndMdcIsCleared() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain exploding = (req, res) -> {
            throw new RuntimeException("boom");
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, exploding))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("boom");
        assertThat(MDC.get(TraceIds.MDC_TRACE_ID)).isNull();
    }
}
