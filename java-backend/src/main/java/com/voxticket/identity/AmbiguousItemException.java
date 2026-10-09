package com.voxticket.identity;

import com.voxticket.persistence.entity.OrderItem;
import java.util.List;

/**
 * Thrown when a natural-language item reference matches more than one item
 * on an order and needs disambiguation. Carries the candidate items (never
 * raw IDs - product names only) so the caller can ask the customer
 * naturally which one they meant.
 */
public class AmbiguousItemException extends RuntimeException {

    private final List<OrderItem> candidates;

    public AmbiguousItemException(List<OrderItem> candidates) {
        super("Item reference matches " + candidates.size() + " items on this order");
        this.candidates = candidates;
    }

    public List<OrderItem> getCandidates() {
        return candidates;
    }
}