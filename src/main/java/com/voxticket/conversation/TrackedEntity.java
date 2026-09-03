package com.voxticket.conversation;

/**
 * Spec §16. {@code relevance} defaults to the recording turn number, so a
 * more recent mention naturally outranks an older one (spec: "the
 * current/recently relevant entity should outweigh historical mentions")
 * without needing a separate decay mechanism. It's kept as its own field
 * rather than reusing {@code sourceTurn} directly so a later phase can
 * adjust relevance independently of when the entity was first introduced -
 * e.g. boosting an entity the agent just asked the customer to confirm.
 */
public record TrackedEntity(EntityType type, String value, int sourceTurn, double confidence, boolean verified, double relevance) {
}