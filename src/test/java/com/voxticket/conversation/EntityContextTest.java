package com.voxticket.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EntityContextTest {

    @Test
    void mostRecentMentionOutranksAnOlderOne() {
        EntityContext context = new EntityContext();
        context.record(EntityType.ORDER_REFERENCE, "ORD-10001", 1, 0.9, false);
        context.record(EntityType.ORDER_REFERENCE, "ORD-20002", 3, 0.9, false);

        var best = context.mostRelevant(EntityType.ORDER_REFERENCE);

        assertThat(best).isPresent();
        assertThat(best.get().value()).isEqualTo("ORD-20002");
    }

    @Test
    void atEqualRelevanceAVerifiedEntityOutranksAnUnverifiedOne() {
        EntityContext context = new EntityContext();
        context.record(EntityType.ORDER_REFERENCE, "ORD-10001", 2, 0.5, false);
        context.record(EntityType.ORDER_REFERENCE, "ORD-20002", 2, 0.5, true);

        var best = context.mostRelevant(EntityType.ORDER_REFERENCE);

        assertThat(best).isPresent();
        assertThat(best.get().value()).isEqualTo("ORD-20002");
        assertThat(best.get().verified()).isTrue();
    }

    @Test
    void reRecordingTheSameValueReplacesRatherThanDuplicates() {
        EntityContext context = new EntityContext();
        context.record(EntityType.ORDER_REFERENCE, "ORD-10001", 1, 0.5, false);
        context.record(EntityType.ORDER_REFERENCE, "ORD-10001", 4, 0.95, true);

        var all = context.all(EntityType.ORDER_REFERENCE);

        assertThat(all).hasSize(1);
        assertThat(all.get(0).sourceTurn()).isEqualTo(4);
        assertThat(all.get(0).verified()).isTrue();
    }

    @Test
    void differentEntityTypesAreTrackedIndependently() {
        EntityContext context = new EntityContext();
        context.record(EntityType.ORDER_REFERENCE, "ORD-10001", 1, 0.9, false);
        context.record(EntityType.TICKET_REFERENCE, "TCK-00001", 1, 0.9, false);

        assertThat(context.all(EntityType.ORDER_REFERENCE)).hasSize(1);
        assertThat(context.all(EntityType.TICKET_REFERENCE)).hasSize(1);
        assertThat(context.mostRelevant(EntityType.REFUND_REFERENCE)).isEmpty();
    }
}