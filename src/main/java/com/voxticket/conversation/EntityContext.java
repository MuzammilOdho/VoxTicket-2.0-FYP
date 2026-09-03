package com.voxticket.conversation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Spec §16. Not thread-safe on its own - it's only ever reached through a
 * {@link ConversationSession}, which is itself only ever mutated inside
 * {@link SessionStore}'s per-session lock.
 *
 * <p>Nothing populates this yet in Phase 4 - there's no NLU to extract
 * entities from free text until Phase 5's SupportAgent exists. This phase
 * only proves the data structure and its ranking rule are correct in
 * isolation, ready for Phase 5 to call {@link #record} once it has
 * something real to record.
 */
public class EntityContext {

    private final Map<EntityType, List<TrackedEntity>> entitiesByType = new EnumMap<>(EntityType.class);

    /** Best-first: highest relevance wins; ties broken by verified over unverified, then by most recent turn. */
    private static final Comparator<TrackedEntity> BEST_FIRST = Comparator.comparingDouble(TrackedEntity::relevance)
            .thenComparing(TrackedEntity::verified)
            .thenComparingInt(TrackedEntity::sourceTurn);

    /** Records a mention, replacing any prior entry for the same (type, value) pair rather than duplicating it. */
    public void record(EntityType type, String value, int sourceTurn, double confidence, boolean verified) {
        List<TrackedEntity> entities = entitiesByType.computeIfAbsent(type, t -> new ArrayList<>());
        entities.removeIf(e -> e.value().equalsIgnoreCase(value));
        entities.add(new TrackedEntity(type, value, sourceTurn, confidence, verified, sourceTurn));
    }

    public Optional<TrackedEntity> mostRelevant(EntityType type) {
        return entitiesByType.getOrDefault(type, List.of()).stream().max(BEST_FIRST);
    }

    public List<TrackedEntity> all(EntityType type) {
        return List.copyOf(entitiesByType.getOrDefault(type, List.of()));
    }
}