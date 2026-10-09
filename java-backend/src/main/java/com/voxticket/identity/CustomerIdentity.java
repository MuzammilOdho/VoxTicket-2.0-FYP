package com.voxticket.identity;

import java.util.UUID;

/**
 * Spec §8. The identity ladder for a conversation. Not yet tied to
 * ConversationSession (that lands in Phase 4) - for now this is constructed
 * directly from a resolved phone number and passed explicitly into service
 * calls.
 *
 * <p>{@code customerId} is null at ANONYMOUS. Every method that needs it
 * must go through {@link #requireCustomerId()} rather than reading the
 * record component directly, so a missing check fails loudly instead of
 * silently passing null further down.
 */
public record CustomerIdentity(UUID customerId, IdentityAssurance assuranceLevel, String phone) {

    public static CustomerIdentity anonymous() {
        return new CustomerIdentity(null, IdentityAssurance.ANONYMOUS, null);
    }

    public boolean isAtLeast(IdentityAssurance required) {
        return assuranceLevel.ordinal() >= required.ordinal();
    }

    public UUID requireCustomerId() {
        if (customerId == null) {
            throw new IllegalStateException(
                    "No customerId available at assurance level " + assuranceLevel + " - caller must check isAtLeast(...) first");
        }
        return customerId;
    }
}