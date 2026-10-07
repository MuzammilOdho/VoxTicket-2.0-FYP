package com.voxticket.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** P0: trace/span ID generation and W3C traceparent parsing. */
class TraceIdsTest {

    @Test
    void newTraceIdIs32LowercaseHex() {
        assertThat(TraceIds.newTraceId()).matches("[0-9a-f]{32}");
    }

    @Test
    void newSpanIdIs16LowercaseHex() {
        assertThat(TraceIds.newSpanId()).matches("[0-9a-f]{16}");
    }

    @Test
    void parseTraceParentExtractsTraceIdFromValidHeader() {
        assertThat(TraceIds.parseTraceParent("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"))
                .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    }

    @Test
    void parseTraceParentRejectsNullBlankAndMalformed() {
        assertThat(TraceIds.parseTraceParent(null)).isNull();
        assertThat(TraceIds.parseTraceParent("")).isNull();
        assertThat(TraceIds.parseTraceParent("   ")).isNull();
        assertThat(TraceIds.parseTraceParent("garbage")).isNull();
        assertThat(TraceIds.parseTraceParent("00-xyz-00f067aa0ba902b7-01")).isNull();
        assertThat(TraceIds.parseTraceParent("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7")).isNull();
    }

    @Test
    void parseTraceParentRejectsForbiddenVersionFf() {
        assertThat(TraceIds.parseTraceParent("ff-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")).isNull();
    }

    @Test
    void isValidTraceIdAcceptsOnlyWellFormedIds() {
        assertThat(TraceIds.isValidTraceId("4bf92f3577b34da6a3ce929d0e0e4736")).isTrue();
        assertThat(TraceIds.isValidTraceId(null)).isFalse();
        assertThat(TraceIds.isValidTraceId("too-short")).isFalse();
        assertThat(TraceIds.isValidTraceId("4BF92F3577B34DA6A3CE929D0E0E4736")).isFalse();
    }
}
