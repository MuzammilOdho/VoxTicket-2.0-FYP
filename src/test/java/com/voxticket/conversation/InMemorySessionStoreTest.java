package com.voxticket.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class InMemorySessionStoreTest {

    @Test
    void aNewSessionIdCreatesASessionOnFirstAccess() {
        InMemorySessionStore store = new InMemorySessionStore();

        Channel channel = store.withSession("s1", Channel.CHAT, ConversationSession::getChannel);

        assertThat(channel).isEqualTo(Channel.CHAT);
    }

    @Test
    void theSameSessionIdReturnsTheSameSessionOnSubsequentAccess() {
        InMemorySessionStore store = new InMemorySessionStore();

        store.withSession("s1", Channel.CHAT, session -> session.recordUserMessage("first"));
        int secondTurn = store.withSession("s1", Channel.CHAT, session -> session.recordUserMessage("second"));

        assertThat(secondTurn).isEqualTo(2); // proves it's the SAME session, not recreated
    }

    @Test
    void concurrentTurnsOnTheSameSessionAreSerializedWithNoLostUpdates() throws InterruptedException {
        InMemorySessionStore store = new InMemorySessionStore();
        int threads = 10;
        int callsPerThread = 50;
        int expectedTotal = threads * callsPerThread;
        ExecutorService executor = Executors.newFixedThreadPool(threads);

        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                for (int i = 0; i < callsPerThread; i++) {
                    store.withSession("shared-session", Channel.CHAT, session -> session.recordUserMessage("hi"));
                }
            });
        }
        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        int finalTurnCount = store.withSession("shared-session", Channel.CHAT, ConversationSession::getTurnCount);

        // If per-session locking were broken, concurrent read-modify-write on turnCount would lose
        // updates and this would come out lower than expectedTotal.
        assertThat(finalTurnCount).isEqualTo(expectedTotal);
    }
}