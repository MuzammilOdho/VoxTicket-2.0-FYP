package com.voxticket.identity;

import java.time.Instant;
import java.util.UUID;

/**
 * Spec §11. Proof that {@code orderId} has already been checked to belong to
 * {@code customerId}, at {@code verifiedAt}, under {@code assuranceLevel}.
 * Downstream code (Phase 3 policy services, Phase 8 procedures) should
 * accept this type instead of a bare order number, so a step later in a
 * procedure can't skip verification by re-supplying a business reference.
 *
 * <p>Spec §11 also lists a {@code sourceTurn} field - omitted here because
 * there is no turn/session concept yet (Phase 4). Add it when
 * ConversationSession exists rather than faking a value now.
 */
public record VerifiedOrderRef(UUID orderId, String orderNumber, UUID customerId, IdentityAssurance assuranceLevel, Instant verifiedAt) {
}