package com.voxticket.service;

import com.voxticket.identity.OwnedOrderItemResolver;
import com.voxticket.identity.VerifiedOrderRef;
import com.voxticket.persistence.entity.Order;
import com.voxticket.persistence.entity.OrderClaim;
import com.voxticket.persistence.entity.OrderItem;
import com.voxticket.persistence.entity.Payment;
import com.voxticket.persistence.entity.SupportTicket;
import com.voxticket.persistence.entity.enums.ClaimReason;
import com.voxticket.persistence.entity.enums.ClaimResolution;
import com.voxticket.persistence.entity.enums.ClaimStatus;
import com.voxticket.persistence.entity.enums.OrderStatus;
import com.voxticket.persistence.entity.enums.RefundReason;
import com.voxticket.persistence.entity.enums.TicketCategory;
import com.voxticket.persistence.entity.enums.TicketPriority;
import com.voxticket.persistence.repository.OrderClaimRepository;
import com.voxticket.persistence.repository.OrderRepository;
import com.voxticket.persistence.repository.PaymentRepository;
import com.voxticket.persistence.repository.SupportTicketRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spec §33/§34. Deliberately minimal - the spec doesn't define eligibility
 * rules for claims the way it does for cancellation/return (only "not
 * against a cancelled order" is enforced here), and replacement fulfillment
 * is explicitly out of scope (spec §33: "Full replacement inventory
 * automation is not required"), so {@link #resolveWithoutRefund} just
 * records the resolution rather than driving any shipping/inventory action.
 */
@Service
@Transactional
public class ClaimService {

    private final OrderRepository orderRepository;
    private final OwnedOrderItemResolver ownedOrderItemResolver;
    private final OrderClaimRepository orderClaimRepository;
    private final SupportTicketRepository supportTicketRepository;
    private final PaymentRepository paymentRepository;
    private final RefundService refundService;
    private final ReferenceNumberGenerator referenceNumberGenerator;

    public ClaimService(
            OrderRepository orderRepository,
            OwnedOrderItemResolver ownedOrderItemResolver,
            OrderClaimRepository orderClaimRepository,
            SupportTicketRepository supportTicketRepository,
            PaymentRepository paymentRepository,
            RefundService refundService,
            ReferenceNumberGenerator referenceNumberGenerator) {
        this.orderRepository = orderRepository;
        this.ownedOrderItemResolver = ownedOrderItemResolver;
        this.orderClaimRepository = orderClaimRepository;
        this.supportTicketRepository = supportTicketRepository;
        this.paymentRepository = paymentRepository;
        this.refundService = refundService;
        this.referenceNumberGenerator = referenceNumberGenerator;
    }

    public OrderClaim fileClaim(
            VerifiedOrderRef orderRef, String sku, ClaimReason reason, ClaimResolution requestedResolution, String customerDescription) {
        Order order = orderRepository.findById(orderRef.orderId()).orElseThrow();
        if (order.getOrderStatus() == OrderStatus.CANCELLED) {
            throw new IllegalStateException("Cannot file a claim against a cancelled order: " + order.getOrderNumber());
        }
        OrderItem item = ownedOrderItemResolver.resolveBySku(orderRef, sku);

        TicketPriority priority = reason == ClaimReason.OTHER ? TicketPriority.MEDIUM : TicketPriority.HIGH;
        SupportTicket ticket = supportTicketRepository.save(new SupportTicket(
                referenceNumberGenerator.ticketNumber(), order.getCustomer(), order, TicketCategory.CLAIM, priority, customerDescription));

        OrderClaim claim = new OrderClaim(referenceNumberGenerator.claimNumber(), order, item, reason, requestedResolution);
        claim.setSupportTicket(ticket);
        return orderClaimRepository.save(claim);
    }

    public OrderClaim markInReview(UUID claimId) {
        OrderClaim claim = get(claimId);
        requireStatus(claim, ClaimStatus.OPEN);
        claim.setStatus(ClaimStatus.IN_REVIEW);
        return claim;
    }

    public OrderClaim resolveWithRefund(UUID claimId, BigDecimal amount) {
        OrderClaim claim = get(claimId);
        requireOpenOrInReview(claim);
        Payment payment = paymentRepository.findFirstByOrderIdOrderByCreatedAtDesc(claim.getOrder().getId()).orElseThrow();
        refundService.initiateRefund(claim.getOrder(), payment, null, amount, RefundReason.CLAIM);
        claim.setStatus(ClaimStatus.RESOLVED);
        return claim;
    }

    public OrderClaim resolveWithoutRefund(UUID claimId) {
        OrderClaim claim = get(claimId);
        requireOpenOrInReview(claim);
        claim.setStatus(ClaimStatus.RESOLVED);
        return claim;
    }

    public OrderClaim reject(UUID claimId) {
        OrderClaim claim = get(claimId);
        requireOpenOrInReview(claim);
        claim.setStatus(ClaimStatus.REJECTED);
        return claim;
    }

    private OrderClaim get(UUID claimId) {
        return orderClaimRepository.findById(claimId).orElseThrow(() -> new IllegalArgumentException("Unknown claim: " + claimId));
    }

    private void requireStatus(OrderClaim claim, ClaimStatus expected) {
        if (claim.getStatus() != expected) {
            throw new IllegalStateException("Claim " + claim.getClaimNumber() + " is " + claim.getStatus() + ", expected " + expected);
        }
    }

    private void requireOpenOrInReview(OrderClaim claim) {
        if (claim.getStatus() != ClaimStatus.OPEN && claim.getStatus() != ClaimStatus.IN_REVIEW) {
            throw new IllegalStateException("Claim " + claim.getClaimNumber() + " is " + claim.getStatus() + ", expected OPEN or IN_REVIEW");
        }
    }
}