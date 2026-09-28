package com.voxticket.routing.eval;

import com.voxticket.routing.SemanticScores;
import com.voxticket.routing.StructuralRoutingSignal;
import java.util.Set;

/**
 * Phase 3: one evaluation example after a single production scoring pass.
 *
 * <p>The semantic scores come from exactly one {@link
 * com.voxticket.routing.SemanticRoutingService#score} call (one query embedding).
 * When a structural signal short-circuited the turn, {@code semanticScores} is
 * {@code null} - the semantic path never ran and no embedding was spent.
 */
public record ScoredExample(
        EvalExample example,
        Set<StructuralRoutingSignal> signals,
        SemanticScores semanticScores) {

    /** True when the structural short-circuit decided (or would decide) this turn. */
    public boolean structuralFired() {
        return !signals.isEmpty();
    }

    /** Convenience accessor for the example's split (VALIDATION vs FINAL_TEST). */
    public EvalSplit split() {
        return example.split();
    }

    /** Convenience accessor for the example's id. */
    public String exampleId() {
        return example.id();
    }
}
