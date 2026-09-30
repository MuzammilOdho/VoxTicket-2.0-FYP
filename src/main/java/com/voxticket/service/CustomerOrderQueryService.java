package com.voxticket.service;

import com.voxticket.identity.CustomerIdentity;
import com.voxticket.identity.IdentityAssurance;
import com.voxticket.identity.InsufficientAssuranceException;
import com.voxticket.identity.OwnedOrderResolver;
import com.voxticket.identity.ResourceNotFoundForAccountException;
import com.voxticket.identity.VerifiedOrderRef;
import com.voxticket.persistence.entity.Order;
import com.voxticket.persistence.entity.OrderClaim;
import com.voxticket.persistence.entity.OrderItem;
import com.voxticket.persistence.entity.Payment;
import com.voxticket.persistence.entity.Refund;
import com.voxticket.persistence.entity.ReturnRequest;
import com.voxticket.persistence.entity.Shipment;
import com.voxticket.persistence.entity.SupportTicket;
import com.voxticket.persistence.entity.enums.ClaimStatus;
import com.voxticket.persistence.entity.enums.PaymentStatus;
import com.voxticket.persistence.entity.enums.RefundStatus;
import com.voxticket.persistence.entity.enums.TicketStatus;
import com.voxticket.persistence.repository.OrderClaimRepository;
import com.voxticket.persistence.repository.OrderRepository;
import com.voxticket.persistence.repository.PaymentRepository;
import com.voxticket.persistence.repository.RefundRepository;
import com.voxticket.persistence.repository.ReturnRequestRepository;
import com.voxticket.persistence.repository.ShipmentRepository;
import com.voxticket.persistence.repository.SupportTicketRepository;
import com.voxticket.policy.CancellationEligibility;
import com.voxticket.policy.CancellationPolicyService;
import com.voxticket.policy.ClaimCapabilityState;
import com.voxticket.policy.ClaimPolicyService;
import com.voxticket.policy.ItemClaimCapability;
import com.voxticket.policy.ReturnEligibility;
import com.voxticket.policy.ReturnPolicyService;
import com.voxticket.service.dto.CancellationCapabilityView;
import com.voxticket.service.dto.ClaimContextView;
import com.voxticket.service.dto.ExistingItemClaimView;
import com.voxticket.service.dto.ItemClaimCapabilityView;
import com.voxticket.service.dto.ItemReturnCapabilityView;
import com.voxticket.service.dto.OrderItemView;
import com.voxticket.service.dto.OrderSummaryView;
import com.voxticket.service.dto.OrderSupportContext;
import com.voxticket.service.dto.OrderSupportItemView;
import com.voxticket.service.dto.PaymentContextView;
import com.voxticket.service.dto.PaymentStatusView;
import com.voxticket.service.dto.RecentOrderView;
import com.voxticket.service.dto.RefundContextView;
import com.voxticket.service.dto.RefundStateView;
import com.voxticket.service.dto.RefundStatusView;
import com.voxticket.service.dto.ReturnContextView;
import com.voxticket.service.dto.ReturnItemContextView;
import com.voxticket.service.dto.ReturnStatusView;
import com.voxticket.service.dto.ShipmentContextView;
import com.voxticket.service.dto.ShipmentStatusView;
import com.voxticket.service.dto.SupportCapabilitiesView;
import com.voxticket.service.dto.TicketStatusView;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CustomerOrderQueryService {

    private static final IdentityAssurance MIN_READ_ASSURANCE = IdentityAssurance.PHONE_MATCHED;
    private static final Set<TicketStatus> OPEN_TICKET_STATUSES = Set.of(TicketStatus.OPEN, TicketStatus.IN_PROGRESS);
    private static final Set<ClaimStatus> OPEN_CLAIM_STATUSES = Set.of(ClaimStatus.OPEN, ClaimStatus.IN_REVIEW);

    private final OwnedOrderResolver ownedOrderResolver;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final ShipmentRepository shipmentRepository;
    private final ReturnRequestRepository returnRequestRepository;
    private final RefundRepository refundRepository;
    private final SupportTicketRepository supportTicketRepository;
    private final OrderClaimRepository orderClaimRepository;
    private final CancellationPolicyService cancellationPolicyService;
    private final ReturnPolicyService returnPolicyService;
    private final ClaimPolicyService claimPolicyService;

    public CustomerOrderQueryService(
            OwnedOrderResolver ownedOrderResolver,
            OrderRepository orderRepository,
            PaymentRepository paymentRepository,
            ShipmentRepository shipmentRepository,
            ReturnRequestRepository returnRequestRepository,
            RefundRepository refundRepository,
            SupportTicketRepository supportTicketRepository,
            OrderClaimRepository orderClaimRepository,
            CancellationPolicyService cancellationPolicyService,
            ReturnPolicyService returnPolicyService,
            ClaimPolicyService claimPolicyService) {
        this.ownedOrderResolver = ownedOrderResolver;
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.shipmentRepository = shipmentRepository;
        this.returnRequestRepository = returnRequestRepository;
        this.refundRepository = refundRepository;
        this.supportTicketRepository = supportTicketRepository;
        this.orderClaimRepository = orderClaimRepository;
        this.cancellationPolicyService = cancellationPolicyService;
        this.returnPolicyService = returnPolicyService;
        this.claimPolicyService = claimPolicyService;
    }

    /**
     * The single authoritative model-facing support-state read for one order.
     * Capability fields are computed deterministically in Java from domain
     * policy ({@link CancellationPolicyService}, {@link ReturnPolicyService},
     * {@link ClaimPolicyService}) - the model must not infer what support
     * actions are available from the raw order/shipment/payment fields alone.
     * Existing returns, claims, and refunds are all reflected so the model
     * does not suggest duplicate actions.
     */
    public OrderSupportContext getOrderSupportContext(CustomerIdentity identity, String orderNumber) {
        requireAssurance(identity);
        VerifiedOrderRef ref = ownedOrderResolver.resolve(identity, orderNumber);
        Order order = orderRepository.findByIdWithItems(ref.orderId()).orElseThrow();
        Optional<Payment> payment = paymentRepository.findFirstByOrderIdOrderByCreatedAtDesc(ref.orderId());
        List<Shipment> shipments = shipmentRepository.findByOrderId(ref.orderId());
        List<ReturnRequest> returns = returnRequestRepository.findByOrderIdWithItems(ref.orderId());
        List<Refund> refunds = refundRepository.findByOrderIdWithDetails(ref.orderId());
        List<OrderClaim> claims = orderClaimRepository.findByOrderIdWithDetails(ref.orderId());

        // Bulk return evaluation reuses the shipments loaded above for the
        // history section, and the return-item query below fetches each
        // item's return request and order item eagerly - the whole context
        // read stays at a flat, bounded statement count no matter how many
        // returns, claims, or items the order carries.
        Map<UUID, ReturnEligibility> returnEligibilityByItem =
                returnPolicyService.evaluateAll(order, order.getItems(), shipments);

        List<OrderSupportItemView> items = order.getItems().stream()
                .map(item -> toOrderSupportItemView(order, item, returnEligibilityByItem.get(item.getId()), claims))
                .toList();

        PaymentContextView paymentView = payment.map(p -> new PaymentContextView(
                p.getMethod(),
                p.getStatus(),
                p.getAmount(),
                amountActuallyCollected(p),
                p.getCurrency())).orElse(null);

        List<ShipmentContextView> shipmentViews = shipments.stream()
                .sorted(Comparator.comparing(Shipment::getUpdatedAt).reversed())
                .map(s -> new ShipmentContextView(s.getCarrier(), s.getTrackingNumber(), s.getStatus(), s.getEstimatedDeliveryAt(), s.getDeliveredAt()))
                .toList();

        CancellationCapabilityView cancellation = toCancellationCapabilityView(order, payment);

        List<ReturnContextView> returnViews = returns.stream()
                .sorted(Comparator.comparing(ReturnRequest::getRequestedAt).reversed())
                .map(r -> new ReturnContextView(
                        r.getReturnNumber(), r.getStatus(), r.getReason().name(), r.getRequestedAt(),
                        r.getApprovedAt(), r.getReceivedAt(), r.getInspectedAt(), r.getCompletedAt(),
                        r.getItems().stream()
                                .map(ri -> new ReturnItemContextView(
                                        ri.getOrderItem().getProductName(),
                                        ri.getQuantity(),
                                        ri.getReason().name(),
                                        ri.getCondition() == null ? null : ri.getCondition().name()))
                                .toList()))
                .toList();

        List<RefundContextView> refundViews = refunds.stream()
                .sorted(Comparator.comparing(Refund::getInitiatedAt).reversed())
                .map(r -> new RefundContextView(
                        r.getRefundNumber(), r.getStatus(), r.getAmount(), r.getReason().name(),
                        r.getInitiatedAt(), r.getCompletedAt(), r.getFailedAt(),
                        r.getReturnRequest() == null ? null : r.getReturnRequest().getReturnNumber()))
                .toList();

        List<ClaimContextView> claimViews = claims.stream()
                .sorted(Comparator.comparing(OrderClaim::getCreatedAt).reversed())
                .map(c -> new ClaimContextView(
                        c.getClaimNumber(), c.getStatus(), c.getReason().name(), c.getRequestedResolution().name(),
                        c.getOrderItem() == null ? null : c.getOrderItem().getProductName(),
                        c.getCreatedAt(),
                        c.getSupportTicket() == null ? null : c.getSupportTicket().getTicketNumber(),
                        c.getSupportTicket() == null ? null : c.getSupportTicket().getStatus().name()))
                .toList();

        // Compact rollup only - the detailed cancellation capability lives
        // once on the context root; no duplicate copy here.
        SupportCapabilitiesView supportCapabilities = new SupportCapabilitiesView(
                items.stream().anyMatch(i -> i.returnCapability().available()),
                items.stream().anyMatch(i -> i.claimCapability().state() == ClaimCapabilityState.AVAILABLE),
                toRefundStateView(refunds));

        return new OrderSupportContext(
                order.getOrderNumber(),
                order.getOrderStatus(),
                order.getFulfillmentStatus(),
                order.getPlacedAt(),
                order.getCurrency(),
                order.getTotalAmount(),
                items,
                paymentView,
                shipmentViews,
                cancellation,
                returnViews,
                refundViews,
                claimViews,
                supportCapabilities);
    }

    /**
     * Model-facing item: no SKU or other internal identifiers. Return and
     * claim capability are evaluated deterministically by the policy
     * services, so the model never derives them from raw flags. The claim
     * state is explicit: an OPEN/IN_REVIEW claim on this item reports
     * {@code EXISTING_ACTIVE} with the claim attached - never a fresh
     * {@code AVAILABLE} opportunity.
     */
    private OrderSupportItemView toOrderSupportItemView(
            Order order, OrderItem item, ReturnEligibility returnEligibility, List<OrderClaim> claims) {
        ItemReturnCapabilityView returnCapability = new ItemReturnCapabilityView(
                returnEligibility.eligible(),
                returnEligibility.denialReason() == null ? null : returnEligibility.denialReason().name(),
                returnEligibility.maxReturnableQuantity());

        OrderClaim activeClaim = claims.stream()
                .filter(c -> OPEN_CLAIM_STATUSES.contains(c.getStatus()))
                .filter(c -> sameOrderItem(c.getOrderItem(), item))
                .findFirst()
                .orElse(null);
        ItemClaimCapability capability = claimPolicyService.evaluateItem(order, activeClaim);
        ExistingItemClaimView existingClaim = activeClaim == null
                ? null
                : new ExistingItemClaimView(activeClaim.getClaimNumber(), activeClaim.getStatus().name(), activeClaim.getReason().name());
        ItemClaimCapabilityView claimCapability = new ItemClaimCapabilityView(
                capability.state(),
                capability.denialReason() == null ? null : capability.denialReason().name(),
                capability.resolution() == null ? null : capability.resolution().name(),
                existingClaim);

        return new OrderSupportItemView(
                item.getProductName(),
                item.getQuantity(),
                item.getUnitPrice(),
                returnCapability,
                claimCapability);
    }

    /**
     * Matches a claim's item to a context item by persisted ID, falling back
     * to entity identity for transient (non-persisted) instances so unit
     * tests without a database behave the same as production.
     */
    private boolean sameOrderItem(OrderItem a, OrderItem b) {
        if (a == null || b == null) {
            return false;
        }
        if (a == b) {
            return true;
        }
        return a.getId() != null && a.getId().equals(b.getId());
    }

    private BigDecimal amountActuallyCollected(Payment payment) {
        return switch (payment.getStatus()) {
            case PAID, PARTIALLY_REFUNDED, REFUNDED -> payment.getAmount();
            case PENDING, AUTHORIZED, FAILED, VOIDED -> BigDecimal.ZERO;
        };
    }

    private CancellationCapabilityView toCancellationCapabilityView(Order order, Optional<Payment> payment) {
        return payment.map(p -> {
                    CancellationEligibility eligibility = cancellationPolicyService.evaluate(order, p);
                    return new CancellationCapabilityView(
                            eligibility.eligible(),
                            eligibility.denialReason() == null ? null : eligibility.denialReason().name(),
                            eligibility.paymentConsequence() == null ? null : eligibility.paymentConsequence().name());
                })
                .orElse(new CancellationCapabilityView(false, "NO_PAYMENT_RECORD", null));
    }

    /**
     * Descriptive refund state only - refund is not a standalone customer
     * action in the current domain, so no eligibility is invented. Failure
     * causes stay absent: the domain persists no authoritative failure
     * reason.
     */
    private RefundStateView toRefundStateView(List<Refund> refunds) {
        if (refunds.isEmpty()) {
            return new RefundStateView(false, null, false, false);
        }
        Refund latest = refunds.stream()
                .max(Comparator.comparing(Refund::getInitiatedAt))
                .orElseThrow();
        boolean hasFailedRefund = refunds.stream().anyMatch(r -> r.getStatus() == RefundStatus.FAILED);
        boolean hasPendingRefund = refunds.stream().anyMatch(r -> r.getStatus() == RefundStatus.PENDING);
        return new RefundStateView(true, latest.getStatus().name(), hasFailedRefund, hasPendingRefund);
    }

    public OrderSummaryView getOrderSummary(CustomerIdentity identity, String orderNumber) {
        requireAssurance(identity);
        VerifiedOrderRef ref = ownedOrderResolver.resolve(identity, orderNumber);
        Order order = orderRepository.findByIdWithItems(ref.orderId()).orElseThrow();
        return toOrderSummaryView(order);
    }

    public List<RecentOrderView> getRecentOrders(CustomerIdentity identity, int limit) {
        requireAssurance(identity);
        int safeLimit = Math.max(1, Math.min(limit, 20));
        return orderRepository
                .findByCustomerIdOrderByPlacedAtDesc(identity.requireCustomerId(), PageRequest.of(0, safeLimit))
                .stream()
                .map(this::toRecentOrderView)
                .toList();
    }

    public List<ShipmentStatusView> getShipmentStatus(CustomerIdentity identity, String orderNumber) {
        requireAssurance(identity);
        VerifiedOrderRef ref = ownedOrderResolver.resolve(identity, orderNumber);
        return shipmentRepository.findByOrderId(ref.orderId()).stream()
                .sorted(Comparator.comparing(Shipment::getUpdatedAt).reversed())
                .map(shipment -> toShipmentStatusView(shipment, ref.orderNumber()))
                .toList();
    }

    public Optional<PaymentStatusView> getPaymentStatus(CustomerIdentity identity, String orderNumber) {
        requireAssurance(identity);
        VerifiedOrderRef ref = ownedOrderResolver.resolve(identity, orderNumber);
        return paymentRepository
                .findFirstByOrderIdOrderByCreatedAtDesc(ref.orderId())
                .map(payment -> toPaymentStatusView(payment, ref.orderNumber()));
    }

    public Optional<RefundStatusView> getLatestRefund(CustomerIdentity identity, String orderNumber) {
        requireAssurance(identity);
        VerifiedOrderRef ref = ownedOrderResolver.resolve(identity, orderNumber);
        return refundRepository.findByOrderIdWithDetails(ref.orderId()).stream()
                .max(Comparator.comparing(Refund::getInitiatedAt))
                .map(refund -> toRefundStatusView(refund, ref.orderNumber()));
    }

    public List<ReturnStatusView> getReturnStatus(CustomerIdentity identity, String orderNumber) {
        requireAssurance(identity);
        VerifiedOrderRef ref = ownedOrderResolver.resolve(identity, orderNumber);
        return returnRequestRepository.findByOrderId(ref.orderId()).stream()
                .sorted(Comparator.comparing(ReturnRequest::getRequestedAt).reversed())
                .map(returnRequest -> toReturnStatusView(returnRequest, ref.orderNumber()))
                .toList();
    }

    public List<TicketStatusView> getOpenTickets(CustomerIdentity identity) {
        requireAssurance(identity);
        return supportTicketRepository.findByCustomerId(identity.requireCustomerId()).stream()
                .filter(ticket -> OPEN_TICKET_STATUSES.contains(ticket.getStatus()))
                .sorted(Comparator.comparing(SupportTicket::getCreatedAt).reversed())
                .map(this::toTicketStatusView)
                .toList();
    }

    public TicketStatusView getTicketStatus(CustomerIdentity identity, String ticketNumber) {
        requireAssurance(identity);
        SupportTicket ticket = supportTicketRepository
                .findByTicketNumberAndCustomerId(ticketNumber, identity.requireCustomerId())
                .orElseThrow(() -> new ResourceNotFoundForAccountException("TICKET", ticketNumber));
        return toTicketStatusView(ticket);
    }

    private void requireAssurance(CustomerIdentity identity) {
        if (!identity.isAtLeast(MIN_READ_ASSURANCE)) {
            throw new InsufficientAssuranceException(MIN_READ_ASSURANCE, identity.assuranceLevel());
        }
    }

    private OrderSummaryView toOrderSummaryView(Order order) {
        List<OrderItemView> items = order.getItems().stream()
                .map(i -> new OrderItemView(i.getProductName(), i.getSku(), i.getQuantity(), i.getUnitPrice(), i.isReturnable(), i.isFinalSale()))
                .toList();
        return new OrderSummaryView(
                order.getOrderNumber(), order.getOrderStatus(), order.getFulfillmentStatus(),
                order.getCurrency(), order.getTotalAmount(), order.getPlacedAt(), items);
    }

    private RecentOrderView toRecentOrderView(Order order) {
        return new RecentOrderView(
                order.getOrderNumber(), order.getOrderStatus(), order.getFulfillmentStatus(),
                order.getTotalAmount(), order.getCurrency(), order.getPlacedAt());
    }

    private ShipmentStatusView toShipmentStatusView(Shipment shipment, String orderNumber) {
        return new ShipmentStatusView(
                orderNumber, shipment.getCarrier(), shipment.getTrackingNumber(), shipment.getStatus(),
                shipment.getEstimatedDeliveryAt(), shipment.getDeliveredAt());
    }

    private PaymentStatusView toPaymentStatusView(Payment payment, String orderNumber) {
        return new PaymentStatusView(orderNumber, payment.getMethod(), payment.getStatus(), payment.getAmount(), payment.getCurrency());
    }

    private RefundStatusView toRefundStatusView(Refund refund, String orderNumber) {
        return new RefundStatusView(
                refund.getRefundNumber(), orderNumber, refund.getAmount(), refund.getPayment().getCurrency(), refund.getStatus(),
                refund.getInitiatedAt(), refund.getCompletedAt(), refund.getFailedAt());
    }

    private ReturnStatusView toReturnStatusView(ReturnRequest returnRequest, String orderNumber) {
        return new ReturnStatusView(
                returnRequest.getReturnNumber(), orderNumber, returnRequest.getStatus(), returnRequest.getReason().name(),
                returnRequest.getRequestedAt(), returnRequest.getCompletedAt());
    }

    private TicketStatusView toTicketStatusView(SupportTicket ticket) {
        return new TicketStatusView(
                ticket.getTicketNumber(), ticket.getCategory(), ticket.getPriority(), ticket.getStatus(),
                ticket.getSummary(), ticket.getCreatedAt(), ticket.getResolvedAt());
    }
}
