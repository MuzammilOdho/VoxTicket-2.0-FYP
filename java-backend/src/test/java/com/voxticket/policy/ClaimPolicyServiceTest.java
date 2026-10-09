package com.voxticket.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.voxticket.persistence.entity.Customer;
import com.voxticket.persistence.entity.Order;
import com.voxticket.persistence.entity.OrderClaim;
import com.voxticket.persistence.entity.OrderItem;
import com.voxticket.persistence.entity.enums.ClaimReason;
import com.voxticket.persistence.entity.enums.ClaimResolution;
import com.voxticket.persistence.entity.enums.ClaimStatus;
import com.voxticket.persistence.entity.enums.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The claim policy is deliberately minimal: the domain defines exactly one
 * eligibility rule (no claims against cancelled orders) and every filed
 * claim resolves through manual review. These tests pin that minimality so
 * future broad claim rules cannot creep in unnoticed.
 */
class ClaimPolicyServiceTest {

    private final ClaimPolicyService policyService = new ClaimPolicyService();

    private Order newOrder() {
        Customer customer = new Customer("Test", "User", "claim." + UUID.randomUUID() + "@example.pk", "+923001234567");
        return new Order("ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), customer,
                "PKR", BigDecimal.valueOf(500), BigDecimal.ZERO, BigDecimal.valueOf(500), Instant.now());
    }

    @Test
    void openOrderIsClaimEligibleWithManualReviewResolution() {
        ClaimEligibility eligibility = policyService.evaluate(newOrder());

        assertThat(eligibility.eligible()).isTrue();
        assertThat(eligibility.denialReason()).isNull();
        assertThat(eligibility.resolution()).isEqualTo(ClaimResolution.MANUAL_REVIEW);
    }

    @Test
    void cancelledOrderCannotFileAClaim() {
        Order order = newOrder();
        order.setOrderStatus(OrderStatus.CANCELLED);

        ClaimEligibility eligibility = policyService.evaluate(order);

        assertThat(eligibility.eligible()).isFalse();
        assertThat(eligibility.denialReason()).isEqualTo(ClaimDenialReason.ORDER_CANCELLED);
        assertThat(eligibility.resolution()).isNull();
    }

    private OrderClaim activeClaim(Order order, OrderItem item, ClaimStatus status) {
        OrderClaim claim = new OrderClaim("CLM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                order, item, ClaimReason.DAMAGED, ClaimResolution.MANUAL_REVIEW);
        claim.setStatus(status);
        return claim;
    }

    @Test
    void openClaimOnItemReportsExistingActiveNotAvailable() {
        Order order = newOrder();
        OrderItem item = new OrderItem("Active Item", "SKU-ACTIVE", 1, BigDecimal.valueOf(500), true, false);

        ItemClaimCapability capability = policyService.evaluateItem(order, activeClaim(order, item, ClaimStatus.OPEN));

        assertThat(capability.state()).isEqualTo(ClaimCapabilityState.EXISTING_ACTIVE);
        assertThat(capability.denialReason()).isNull();
        assertThat(capability.resolution()).isNull();
    }

    @Test
    void inReviewClaimOnItemReportsExistingActive() {
        Order order = newOrder();
        OrderItem item = new OrderItem("Review Item", "SKU-REVIEW", 1, BigDecimal.valueOf(500), true, false);

        ItemClaimCapability capability = policyService.evaluateItem(order, activeClaim(order, item, ClaimStatus.IN_REVIEW));

        assertThat(capability.state()).isEqualTo(ClaimCapabilityState.EXISTING_ACTIVE);
    }

    @Test
    void resolvedClaimLeavesItemAvailable() {
        Order order = newOrder();
        OrderItem item = new OrderItem("Resolved Item", "SKU-RES", 1, BigDecimal.valueOf(500), true, false);

        ItemClaimCapability capability = policyService.evaluateItem(order, activeClaim(order, item, ClaimStatus.RESOLVED));

        assertThat(capability.state()).isEqualTo(ClaimCapabilityState.AVAILABLE);
        assertThat(capability.resolution()).isEqualTo(ClaimResolution.MANUAL_REVIEW);
    }

    @Test
    void rejectedClaimLeavesItemAvailable() {
        Order order = newOrder();
        OrderItem item = new OrderItem("Rejected Item", "SKU-REJ", 1, BigDecimal.valueOf(500), true, false);

        ItemClaimCapability capability = policyService.evaluateItem(order, activeClaim(order, item, ClaimStatus.REJECTED));

        assertThat(capability.state()).isEqualTo(ClaimCapabilityState.AVAILABLE);
    }

    @Test
    void cancelledOrderIsUnavailableEvenWithActiveClaim() {
        Order order = newOrder();
        order.setOrderStatus(OrderStatus.CANCELLED);
        OrderItem item = new OrderItem("Cancelled Item", "SKU-CX", 1, BigDecimal.valueOf(500), true, false);

        ItemClaimCapability capability = policyService.evaluateItem(order, activeClaim(order, item, ClaimStatus.OPEN));

        assertThat(capability.state()).isEqualTo(ClaimCapabilityState.UNAVAILABLE);
        assertThat(capability.denialReason()).isEqualTo(ClaimDenialReason.ORDER_CANCELLED);
    }

    @Test
    void itemWithoutClaimIsAvailable() {
        ItemClaimCapability capability = policyService.evaluateItem(newOrder(), null);

        assertThat(capability.state()).isEqualTo(ClaimCapabilityState.AVAILABLE);
        assertThat(capability.resolution()).isEqualTo(ClaimResolution.MANUAL_REVIEW);
    }

    @Test
    void claimPolicyNeverPromisesRefundReplacementApprovalOrTimeframe() {
        ClaimEligibility eligibility = policyService.evaluate(newOrder());

        assertThat(eligibility.resolution()).isNotEqualTo(ClaimResolution.REFUND);
        assertThat(eligibility.resolution()).isNotEqualTo(ClaimResolution.REPLACEMENT);
    }
}
