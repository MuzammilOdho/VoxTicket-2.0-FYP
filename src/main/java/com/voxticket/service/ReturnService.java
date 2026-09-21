package com.voxticket.service;

import com.voxticket.identity.OwnedOrderItemResolver;
import com.voxticket.identity.VerifiedOrderRef;
import com.voxticket.persistence.entity.Order;
import com.voxticket.persistence.entity.OrderItem;
import com.voxticket.persistence.entity.Payment;
import com.voxticket.persistence.entity.ReturnItem;
import com.voxticket.persistence.entity.ReturnRequest;
import com.voxticket.persistence.entity.enums.RefundReason;
import com.voxticket.persistence.entity.enums.ReturnReason;
import com.voxticket.persistence.entity.enums.ReturnStatus;
import com.voxticket.persistence.repository.OrderItemRepository;
import com.voxticket.persistence.repository.OrderRepository;
import com.voxticket.persistence.repository.PaymentRepository;
import com.voxticket.persistence.repository.ReturnRequestRepository;
import com.voxticket.policy.ReturnEligibility;
import com.voxticket.policy.ReturnNotEligibleException;
import com.voxticket.policy.ReturnPolicyService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spec §36. Drives the return lifecycle: REQUESTED -> APPROVED -> (IN_TRANSIT) -> RECEIVED -> INSPECTED -> COMPLETED/REJECTED,
 * or CANCELLED at any point before COMPLETED. Same security note as
 * {@link CancellationService}: {@link #requestReturn} is the mutation Phase 8
 * must gate behind confirmation/OTP, not a self-contained safe endpoint.
 *
 * <p>Deliberately out of scope for this phase: recording per-item
 * {@code ItemCondition} at inspection time. The entity supports it
 * (spec §31); wiring an inspection UI/flow through it can be added later
 * without changing this service's shape.
 */
@Service
@Transactional
public class ReturnService {

    private final OrderRepository orderRepository;
    private final OwnedOrderItemResolver ownedOrderItemResolver;
    private final ReturnRequestRepository returnRequestRepository;
    private final ReturnPolicyService returnPolicyService;
    private final RefundService refundService;
    private final PaymentRepository paymentRepository;
    private final ReferenceNumberGenerator referenceNumberGenerator;
    private final OrderItemRepository orderItemRepository;

    public ReturnService(
            OrderRepository orderRepository,
            OwnedOrderItemResolver ownedOrderItemResolver,
            ReturnRequestRepository returnRequestRepository,
            ReturnPolicyService returnPolicyService,
            RefundService refundService,
            PaymentRepository paymentRepository,
            ReferenceNumberGenerator referenceNumberGenerator, OrderItemRepository orderItemRepository) {
        this.orderRepository = orderRepository;
        this.ownedOrderItemResolver = ownedOrderItemResolver;
        this.returnRequestRepository = returnRequestRepository;
        this.returnPolicyService = returnPolicyService;
        this.refundService = refundService;
        this.paymentRepository = paymentRepository;
        this.referenceNumberGenerator = referenceNumberGenerator;
        this.orderItemRepository = orderItemRepository;
    }

    public ReturnRequest requestReturn(VerifiedOrderRef orderRef, String sku, int quantity, ReturnReason reason) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }

        // Gap-fix: authoritative revalidation at execution time, mirroring CancellationService's
        // existing pattern - the eligibility check ProcedureCoordinator ran earlier must not be
        // trusted as still valid by the time this actually executes.
        Order order = orderRepository.findById(orderRef.orderId()).orElseThrow();
        OrderItem item = orderItemRepository.findByOrderId(orderRef.orderId()).stream()
                .filter(i -> i.getSku().equalsIgnoreCase(sku))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Item not found during return execution: " + sku));
        ReturnEligibility recheck = returnPolicyService.evaluate(order, item);
        if (!recheck.eligible()) {
            throw new ReturnNotEligibleException(orderRef.orderNumber() + " is no longer eligible for return: " , recheck.denialReason());
        }

        if (quantity > recheck.maxReturnableQuantity()) {
            throw new ReturnNotEligibleException(order.getOrderNumber(), com.voxticket.policy.ReturnDenialReason.NO_REMAINING_RETURNABLE_QUANTITY);
        }

        ReturnRequest returnRequest = new ReturnRequest(referenceNumberGenerator.returnNumber(), order, reason);
        returnRequest.addItem(new ReturnItem(item, quantity, reason));
        return returnRequestRepository.save(returnRequest);
    }

    public ReturnRequest approve(UUID returnRequestId) {
        ReturnRequest returnRequest = get(returnRequestId);
        requireStatus(returnRequest, ReturnStatus.REQUESTED);
        returnRequest.setStatus(ReturnStatus.APPROVED);
        returnRequest.setApprovedAt(Instant.now());
        return returnRequest;
    }

    public ReturnRequest markInTransit(UUID returnRequestId) {
        ReturnRequest returnRequest = get(returnRequestId);
        requireStatus(returnRequest, ReturnStatus.APPROVED);
        returnRequest.setStatus(ReturnStatus.IN_TRANSIT);
        return returnRequest;
    }

    public ReturnRequest markReceived(UUID returnRequestId) {
        ReturnRequest returnRequest = get(returnRequestId);
        requireOneOf(returnRequest, ReturnStatus.APPROVED, ReturnStatus.IN_TRANSIT);
        returnRequest.setStatus(ReturnStatus.RECEIVED);
        returnRequest.setReceivedAt(Instant.now());
        return returnRequest;
    }

    /** Spec §37: inspection decides pass/fail; a pass here creates the resulting Refund (spec §36: do not refund before inspection). */
    public ReturnRequest completeInspection(UUID returnRequestId, boolean approvedForRefund) {
        ReturnRequest returnRequest = get(returnRequestId);
        requireStatus(returnRequest, ReturnStatus.RECEIVED);
        returnRequest.setInspectedAt(Instant.now());

        if (!approvedForRefund) {
            returnRequest.setStatus(ReturnStatus.REJECTED);
            return returnRequest;
        }

        returnRequest.setStatus(ReturnStatus.COMPLETED);
        returnRequest.setCompletedAt(Instant.now());

        Payment payment = paymentRepository.findFirstByOrderIdOrderByCreatedAtDesc(returnRequest.getOrder().getId()).orElseThrow();
        BigDecimal refundAmount = returnRequest.getItems().stream()
                .map(ri -> ri.getOrderItem().getUnitPrice().multiply(BigDecimal.valueOf(ri.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        refundService.initiateRefund(returnRequest.getOrder(), payment, returnRequest, refundAmount, RefundReason.RETURN);
        return returnRequest;
    }

    public ReturnRequest cancel(UUID returnRequestId) {
        ReturnRequest returnRequest = get(returnRequestId);
        if (returnRequest.getStatus() == ReturnStatus.COMPLETED
                || returnRequest.getStatus() == ReturnStatus.REJECTED
                || returnRequest.getStatus() == ReturnStatus.CANCELLED) {
            throw new IllegalStateException("Cannot cancel return " + returnRequest.getReturnNumber() + " in terminal status " + returnRequest.getStatus());
        }
        returnRequest.setStatus(ReturnStatus.CANCELLED);
        return returnRequest;
    }

    private ReturnRequest get(UUID returnRequestId) {
        return returnRequestRepository.findById(returnRequestId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown return request: " + returnRequestId));
    }

    private void requireStatus(ReturnRequest returnRequest, ReturnStatus expected) {
        if (returnRequest.getStatus() != expected) {
            throw new IllegalStateException("Return " + returnRequest.getReturnNumber() + " is " + returnRequest.getStatus() + ", expected " + expected);
        }
    }

    private void requireOneOf(ReturnRequest returnRequest, ReturnStatus... allowed) {
        for (ReturnStatus status : allowed) {
            if (returnRequest.getStatus() == status) {
                return;
            }
        }
        throw new IllegalStateException(
                "Return " + returnRequest.getReturnNumber() + " is " + returnRequest.getStatus() + ", expected one of " + Arrays.toString(allowed));
    }
}