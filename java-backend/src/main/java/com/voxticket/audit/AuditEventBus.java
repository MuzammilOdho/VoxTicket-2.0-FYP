package com.voxticket.audit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Collection;
import java.util.concurrent.LinkedBlockingQueue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * P1 (non-blocking audit). Bounded in-memory queue decoupling telemetry
 * production (the conversation path) from telemetry persistence (the
 * {@link AuditBatchWriter} thread).
 *
 * <p>Contract:
 * <ul>
 *   <li>{@link #publish(AuditEvent)} never throws and never blocks the
 *       caller: it is a single {@link LinkedBlockingQueue#offer} plus, on
 *       overflow, one counter increment.</li>
 *   <li>On overflow the event is dropped and
 *       {@code voxticket.telemetry.dropped{reason=overflow}} increments.
 *       Dropping telemetry - never the conversation - is the correct
 *       backpressure policy.</li>
 *   <li>The queue is FIFO; the single writer thread drains it in order, so
 *       per-session event ordering is preserved end to end.</li>
 * </ul>
 */
@Component
public class AuditEventBus {

    private final LinkedBlockingQueue<AuditEvent> queue;
    private final Counter droppedOverflow;

    /**
     * Set once by {@link AuditBatchWriter} from its {@code @PostConstruct}
     * (registration, not injection, so there is no bean-creation cycle:
     * the writer is constructed with the bus, then registers itself).
     */
    private volatile AuditBatchWriter writer;

    public AuditEventBus(
            @Value("${voxticket.audit.queue-capacity:8192}") int capacity,
            MeterRegistry registry) {
        this.queue = new LinkedBlockingQueue<>(Math.max(1, capacity));
        this.droppedOverflow = Counter.builder("voxticket.telemetry.dropped")
                .tag("reason", "overflow")
                .description("Audit/telemetry events dropped because the bounded queue was full")
                .register(registry);
        Gauge.builder("voxticket.audit.queue.depth", queue, LinkedBlockingQueue::size)
                .description("Current depth of the async audit event queue")
                .register(registry);
    }

    /**
     * Called by {@link AuditBatchWriter} after it is constructed. Plain
     * method (deliberately NOT {@code @Autowired}): setter injection would
     * reintroduce the bus &lt;-&gt; writer bean-creation cycle.
     */
    public void registerWriter(AuditBatchWriter writer) {
        this.writer = writer;
    }

    /**
     * Enqueues one telemetry event. Never throws, never blocks: a null event
     * is ignored, a full queue drops the event and counts it.
     */
    public void publish(AuditEvent event) {
        try {
            if (event == null) {
                return;
            }
            if (!queue.offer(event)) {
                droppedOverflow.increment();
            }
        } catch (Exception ignored) {
            // The bus must never break the conversation path, whatever happens.
        }
    }

    /** Current number of events waiting to be persisted. */
    public int depth() {
        return queue.size();
    }

    /**
     * Test hook: drains up to {@code maxElements} events into
     * {@code target} without involving the writer.
     */
    public void drainTo(Collection<? super AuditEvent> target, int maxElements) {
        queue.drainTo(target, maxElements);
    }

    /**
     * Synchronously drains the queue through the writer. Used by tests that
     * need deterministic persistence; production draining happens on the
     * writer's own thread.
     */
    public void flush() {
        AuditBatchWriter w = this.writer;
        if (w != null) {
            w.drain();
        }
    }
}
