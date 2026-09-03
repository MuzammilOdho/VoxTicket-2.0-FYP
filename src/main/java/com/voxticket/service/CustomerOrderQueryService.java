package com.voxticket.service;

import com.voxticket.identity.CustomerIdentity;
import com.voxticket.identity.IdentityAssurance;
import com.voxticket.identity.InsufficientAssuranceException;
import com.voxticket.identity.OwnedOrderItemResolver;
import com.voxticket.identity.OwnedOrderResolver;
import com.voxticket.identity.ResourceNotFoundForAccountException;
import com.voxticket.identity.VerifiedOrderRef;
import com.voxticket.persistence.entity.Order;
import com.voxticket.persistence.entity.OrderItem;
import com.voxticket.persistence.entity.Payment;
import com.voxticket.persistence.entity.Refund;
import com.voxticket.persistence.entity.ReturnRequest;
import com.voxticket.persistence.entity.Shipment;
import com.voxticket.persistence.entity.SupportTicket;
import com.voxticket.persistence.entity.enums.TicketStatus;
import com.voxticket.persistence.repository.OrderRepository;
import com.voxticket.persistence.repository.PaymentRepository;
import com.voxticket.persistence.repository.RefundRepository;
import com.voxticket.persistence.repository.ReturnRequestRepository;
import com.voxticket.persistence.repository.ShipmentRepository;
import com.voxticket.persistence.repository.SupportTicketRepository;
import com.voxticket.policy.CancellationEligibility;
import com.voxticket.policy.CancellationPolicyService;
import com.voxticket.policy.ReturnEligibility;
import com.voxticket.policy.ReturnPolicyService;
import com.voxticket.service.dto.CancellationEligibilityView;
import com.voxticket.service.dto.OrderItemView;
import com.voxticket.service.dto.OrderSummaryView;
import com.voxticket.service.dto.PaymentStatusView;
import com.voxticket.service.dto.RecentOrderView;
import com.voxticket.service.dto.RefundStatusView;
import com.voxticket.service.dto.ReturnEligibilityView;
import com.voxticket.service.dto.ReturnStatusView;
import com.voxticket.service.dto.ShipmentStatusView;
import com.voxticket.service.dto.TicketStatusView;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spec §41. Every method here is customer-scoped: assurance is checked
 * first, then order (and, where relevant, item) ownership is checked
 * through {@link OwnedOrderResolver}/{@link OwnedOrderItemResolver} before
 * any child data is fetched.
 *
 * <p>This is the ONLY service Phase 5's AI-facing read tools may call.
 * Nothing here accepts a caller-supplied customerId - it always comes from
 * the {@link CustomerIdentity} the caller already resolved. The two
 * eligibility-check methods added in Phase 3 are reads only - they report
 * what Phase 3's policy services would decide, they do not create or
 * mutate anything.
 */
@Service
@Transactional(readOnly = true)
public class CustomerOrderQueryService {

    private static final IdentityAssurance MIN_READ_ASSURANCE = IdentityAssurance.PHONE_MATCHED;
    private static final Set<TicketStatus> OPEN_TICKET_STATUSES = Set.of(TicketStatus.OPEN, TicketStatus.IN_PROGRESS);

    private final OwnedOrderResolver ownedOrderResolver;
    private final OwnedOrderItemResolver ownedOrderItemResolver;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final ShipmentRepository shipmentRepository;
    private final ReturnRequestRepository returnRequestRepository;
    private final RefundRepository refundRepository;
    private final SupportTicketRepository supportTicketRepository;
    private final CancellationPolicyService cancellationPolicyService;
    private final ReturnPolicyService returnPolicyService;

    public CustomerOrderQueryService(
            OwnedOrderResolver ownedOrderResolver,
            OwnedOrderItemResolver ownedOrderItemResolver,
            OrderRepository orderRepository,
            PaymentRepository paymentRepository,
            ShipmentRepository shipmentRepository,
            ReturnRequestRepository returnRequestRepository,
            RefundRepository refundRepository,
            SupportTicketRepository supportTicketRepository,
            CancellationPolicyService cancellationPolicyService,
            ReturnPolicyService returnPolicyService) {
        this.ownedOrderResolver = ownedOrderResolver;
        this.ownedOrderItemResolver = ownedOrderItemResolver;
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.shipmentRepository = shipmentRepository;
        this.returnRequestRepository = returnRequestRepository;
        this.refundRepository = refundRepository;
        this.supportTicketRepository = supportTicketRepository;
        this.cancellationPolicyService = cancellationPolicyService;
        this.returnPolicyService = returnPolicyService;
    }

    public OrderSummaryView getOrderSummary(CustomerIdentity identity, String orderNumber) {
        requireAssurance(identity);
        VerifiedOrderRef ref = ownedOrderResolver.resolve(identity, orderNumber);
        Order order = orderRepository.findById(ref.orderId()).orElseThrow();
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
        return refundRepository.findByOrderId(ref.orderId()).stream()
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

    public CancellationEligibilityView checkCancellationEligibility(CustomerIdentity identity, String orderNumber) {
        requireAssurance(identity);
        VerifiedOrderRef ref = ownedOrderResolver.resolve(identity, orderNumber);
        Order order = orderRepository.findById(ref.orderId()).orElseThrow();
        Payment payment = paymentRepository
                .findFirstByOrderIdOrderByCreatedAtDesc(ref.orderId())
                .orElseThrow(() -> new IllegalStateException("Order has no payment record: " + orderNumber));

        CancellationEligibility eligibility = cancellationPolicyService.evaluate(order, payment);
        return new CancellationEligibilityView(
                orderNumber,
                eligibility.eligible(),
                eligibility.denialReason() == null ? null : eligibility.denialReason().name(),
                eligibility.paymentConsequence() == null ? null : eligibility.paymentConsequence().name());
    }

    public ReturnEligibilityView checkReturnEligibility(CustomerIdentity identity, String orderNumber, String sku) {
        requireAssurance(identity);
        VerifiedOrderRef ref = ownedOrderResolver.resolve(identity, orderNumber);
        Order order = orderRepository.findById(ref.orderId()).orElseThrow();
        OrderItem item = ownedOrderItemResolver.resolveBySku(ref, sku);

        ReturnEligibility eligibility = returnPolicyService.evaluate(order, item);
        return new ReturnEligibilityView(
                orderNumber,
                sku,
                eligibility.eligible(),
                eligibility.denialReason() == null ? null : eligibility.denialReason().name(),
                eligibility.maxReturnableQuantity());
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