package com.voxticket.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.voxticket.conversation.Channel;
import com.voxticket.observability.TraceIds;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * P1: the bounded queue contract - FIFO, bounded, overflow drops (counted)
 * and never throws or blocks.
 */
class AuditEventBusTest {

    private static AuditEvent.SessionTouch touch(String sessionId) {
        return new AuditEvent.SessionTouch(sessionId, Channel.CHAT, null, "ANONYMOUS", false, 0, null,
                TraceIds.newTraceId(), Instant.now());
    }

    @Test
    void publishEnqueuesUpToCapacityThenDropsWithCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AuditEventBus bus = new AuditEventBus(2, registry);

        bus.publish(touch("s1"));
        bus.publish(touch("s2"));
        assertThat(bus.depth()).isEqualTo(2);

        // Third publish overflows: dropped, counted, never thrown.
        assertThatCode(() -> bus.publish(touch("s3"))).doesNotThrowAnyException();
        assertThat(bus.depth()).isEqualTo(2);
        assertThat(registry.counter("voxticket.telemetry.dropped", "reason", "overflow").count()).isEqualTo(1.0);
    }

    @Test
    void publishIgnoresNullEvents() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AuditEventBus bus = new AuditEventBus(2, registry);

        assertThatCode(() -> bus.publish(null)).doesNotThrowAnyException();
        assertThat(bus.depth()).isZero();
    }

    @Test
    void drainToRemovesEventsInFifoOrder() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AuditEventBus bus = new AuditEventBus(8, registry);

        bus.publish(touch("first"));
        bus.publish(touch("second"));

        List<AuditEvent> drained = new ArrayList<>();
        bus.drainTo(drained, 8);

        assertThat(drained).hasSize(2);
        assertThat(((AuditEvent.SessionTouch) drained.get(0)).sessionId()).isEqualTo("first");
        assertThat(((AuditEvent.SessionTouch) drained.get(1)).sessionId()).isEqualTo("second");
        assertThat(bus.depth()).isZero();
    }

    @Test
    void queueDepthGaugeReflectsCurrentDepth() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AuditEventBus bus = new AuditEventBus(8, registry);

        bus.publish(touch("s1"));
        assertThat(registry.get("voxticket.audit.queue.depth").gauge().value()).isEqualTo(1.0);
        bus.publish(touch("s2"));
        assertThat(registry.get("voxticket.audit.queue.depth").gauge().value()).isEqualTo(2.0);
    }
}
