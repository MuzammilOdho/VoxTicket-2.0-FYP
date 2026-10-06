package com.voxticket.policy;

import com.voxticket.persistence.entity.Order;
import com.voxticket.persistence.entity.OrderClaim;
import com.voxticket.persistence.entity.enums.ClaimResolution;
import com.voxticket.persistence.entity.enums.ClaimStatus;
import com.voxticket.persistence.entity.enums.OrderStatus;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * The authoritative claim-filing policy. Deliberately minimal: the domain
 * defines exactly one eligibility rule for claims - a claim cannot be filed
 * against a cancelled order (enforced at execution time by
 * {@code ClaimService.fileClaim} and {@code ProcedureCoordinator}). No broad
 * claim rules are invented here.
 *
 * <p>Every claim the current product files resolves through manual review
 * (the coordinator always files with {@code ClaimResolution.MANUAL_REVIEW}),
 * so the policy reports that resolution. It is informational - never a
 * promise of refund, replacement, approval, or timeframe.
 *
 * <p>Pure and side-effect-free: takes the entity directly, never queries a
 * repository, mirroring {@link CancellationPolicyService}.
 */
@Service
public class ClaimPolicyService {

    /** The claim statuses that count as "already has a live claim" for capability purposes. */
    private static final Set<ClaimStatus> ACTIVE_CLAIM_STATUSES = Set.of(ClaimStatus.OPEN, ClaimStatus.IN_REVIEW);

    public ClaimEligibility evaluate(Order order) {
        if (order.getOrderStatus() == OrderStatus.CANCELLED) {
            return ClaimEligibility.denied(ClaimDenialReason.ORDER_CANCELLED);
        }
        return ClaimEligibility.eligible(ClaimResolution.MANUAL_REVIEW);
    }

    /**
     * Per-item claim capability. Cancelled orders can never file; an
     * OPEN/IN_REVIEW claim on this item reports {@code EXISTING_ACTIVE} with
     * the claim record attached so the model sees it instead of treating the
     * item as a fresh claim opportunity. Resolved/rejected claims leave the
     * item {@code AVAILABLE} - the domain defines no refiling constraint.
     *
     * <p>{@code existingActiveClaim} is the caller's open/in-review claim for
     * this item, if any; the policy verifies its status rather than trusting
     * the caller's filter.
     */
    public ItemClaimCapability evaluateItem(Order order, OrderClaim existingActiveClaim) {
        if (order.getOrderStatus() == OrderStatus.CANCELLED) {
            return ItemClaimCapability.unavailable(ClaimDenialReason.ORDER_CANCELLED);
        }
        if (existingActiveClaim != null && ACTIVE_CLAIM_STATUSES.contains(existingActiveClaim.getStatus())) {
            return ItemClaimCapability.existingActive();
        }
        return ItemClaimCapability.available(ClaimResolution.MANUAL_REVIEW);
    }
}
