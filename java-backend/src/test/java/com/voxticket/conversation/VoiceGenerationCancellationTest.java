package com.voxticket.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Voice barge-in generation: a superseded turn must abort cooperatively
 * instead of running to completion behind the barge-in turn.
 *
 * <p>Contract under test:
 * <ol>
 *   <li>Each voice turn mints a new generation via
 *       {@link SessionStore#nextVoiceGeneration}.</li>
 *   <li>A turn whose generation no longer matches
 *       {@link SessionStore#currentVoiceGeneration} throws
 *       {@link TurnAbortedException} on its next delta emit.</li>
 *   <li>Deterministic mutations (confirmation, OTP submit) check the
 *       generation before committing.</li>
 *   <li>Chat turns (no generation metadata) are unaffected.</li>
 * </ol>
 */
class VoiceGenerationCancellationTest {

    private static final String GEN_KEY = ConversationRuntime.METADATA_VOICE_GENERATION;

    @Test
    void generationsAreMonotonicPerSession() {
        InMemorySessionStore store = new InMemorySessionStore();

        long g1 = store.nextVoiceGeneration("room-1");
        long g2 = store.nextVoiceGeneration("room-1");
        long other = store.nextVoiceGeneration("room-2");

        assertThat(g2).isGreaterThan(g1);
        assertThat(other).isEqualTo(1);
        assertThat(store.currentVoiceGeneration("room-1")).isEqualTo(g2);
        assertThat(store.currentVoiceGeneration("never-seen")).isZero();
    }

    @Test
    void supersededTurnAbortsOnNextDelta() {
        InMemorySessionStore store = new InMemorySessionStore();
        ConversationRuntime runtime = newTestRuntime(store);

        long gen1 = store.nextVoiceGeneration("room-1");
        AtomicReference<Consumer<String>> sinkRef = new AtomicReference<>();
        StringBuilder emitted = new StringBuilder();

        // Simulate a turn that captures its sink but hasn't emitted yet.
        store.withSession("room-1", Channel.PHONE, session -> {
            Consumer<String> sink = runtime.generationCheckedSink(
                    session.getSessionId(), gen1, emitted::append);
            sinkRef.set(sink);
            return null;
        });

        // Barge-in: a new turn mints the next generation.
        store.nextVoiceGeneration("room-1");

        // The old turn's next emit must abort.
        assertThatThrownBy(() -> sinkRef.get().accept("stale delta"))
                .isInstanceOf(TurnAbortedException.class);
        assertThat(emitted.toString()).isEmpty();
    }

    @Test
    void currentGenerationTurnEmitsNormally() {
        InMemorySessionStore store = new InMemorySessionStore();
        ConversationRuntime runtime = newTestRuntime(store);

        long gen1 = store.nextVoiceGeneration("room-1");
        StringBuilder emitted = new StringBuilder();
        store.withSession("room-1", Channel.PHONE, session -> {
            Consumer<String> sink = runtime.generationCheckedSink(
                    session.getSessionId(), gen1, emitted::append);
            sink.accept("hello");
            return null;
        });

        assertThat(emitted.toString()).isEqualTo("hello");
    }

    @Test
    void zeroGenerationDisablesTracking() {
        InMemorySessionStore store = new InMemorySessionStore();
        ConversationRuntime runtime = newTestRuntime(store);

        // Chat turn: generation 0, then a voice turn bumps the counter.
        StringBuilder emitted = new StringBuilder();
        store.withSession("room-1", Channel.CHAT, session -> {
            Consumer<String> sink = runtime.generationCheckedSink(
                    session.getSessionId(), 0, emitted::append);
            sink.accept("chat delta");
            return null;
        });
        store.nextVoiceGeneration("room-1");

        // The untracked sink still emits: chat turns are unaffected by
        // voice generations.
        store.withSession("room-1", Channel.CHAT, session -> {
            Consumer<String> sink = runtime.generationCheckedSink(
                    session.getSessionId(), 0, emitted::append);
            sink.accept(" more");
            return null;
        });

        assertThat(emitted.toString()).isEqualTo("chat delta more");
    }

    @Test
    void metadataKeyIsStable() {
        assertThat(GEN_KEY).isEqualTo("voiceGeneration");
    }

    // Minimal runtime instance: the generation helpers under test do not
    // touch the agent, so null collaborators are safe here.
    private static ConversationRuntime newTestRuntime(InMemorySessionStore store) {
        return new ConversationRuntime(
                store, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void userTurnCarriesGenerationInMetadata() {
        UserTurn turn = new UserTurn("s", Channel.PHONE, "hi", null, null,
                Map.of(GEN_KEY, "42"));
        assertThat(turn.providerMetadata().get(GEN_KEY)).isEqualTo("42");
    }
}
