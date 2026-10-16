package com.voxticket.conversation;

import java.util.Objects;

/**
 * P2.1: one thing the system owes the caller.
 *
 * @param type        the kind of obligation
 * @param detail      customer-visible detail, e.g. the deferred request's
 *                    description or the value awaiting confirmation; never
 *                    carries IDs, SKUs, or secrets
 * @param createdTurn the turn that created the obligation
 */
public record Obligation(ObligationType type, String detail, int createdTurn) {

    public Obligation {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(detail, "detail");
    }
}
