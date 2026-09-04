package com.voxticket.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.voxticket.identity.IdentityAssurance;
import com.voxticket.identity.VerifiedOrderRef;
import com.voxticket.persistence.entity.Customer;
import com.voxticket.persistence.entity.Order;
import com.voxticket.persistence.entity.OrderItem;
import com.voxticket.persistence.entity.Payment;
import com.voxticket.persistence.entity.Refund;
import com.voxticket.persistence.entity.ReturnRequest;
import com.voxticket.persistence.entity.Shipment;
import com.voxticket.persistence.entity.enums.OrderStatus;
import com.voxticket.persistence.entity.enums.PaymentMethod;
import com.voxticket.persistence.entity.enums.PaymentStatus;
import com.voxticket.persistence.entity.enums.RefundReason;
import com.voxticket.persistence.entity.enums.RefundStatus;
import com.voxticket.persistence.entity.enums.ReturnReason;
import com.voxticket.persistence.entity.enums.ReturnStatus;
import com.voxticket.persistence.entity.enums.ShipmentStatus;
import com.voxticket.persistence.repository.CustomerRepository;
import com.voxticket.persistence.repository.OrderRepository;
import com.voxticket.persistence.repository.PaymentRepository;
import com.voxticket.persistence.repository.RefundRepository;
import com.voxticket.persistence.repository.ShipmentRepository;
import com.voxticket.policy.CancellationDenialReason;
import com.voxticket.policy.CancellationNotEligibleException;
import com.voxticket.policy.PaymentConsequence;
import com.voxticket.policy.ReturnDenialReason;
import com.voxticket.policy.ReturnNotEligibleException;
import com.voxticket.persistence.entity.enums.ClaimReason;
import com.voxticket.persistence.entity.enums.ClaimResolution;
import com.voxticket.persistence.entity.enums.ClaimStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * FIX: class-level @Transactional. Without it, each repository call commits
 * its own transaction, so (a) an entity returned by save() is detached the
 * instant the call returns - a setter called afterward on it is silently
 * lost unless something saves it again - and (b) rows from one test
 * method's setUp() are never rolled back, so they can collide with the
 * next test method's "unique" data. Wrapping each test in one transaction
 * that Spring rolls back afterward fixes both.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
@Transactional
class CommerceServicesIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private ShipmentRepository shipmentRepository;
    @Autowired
    private RefundRepository refundRepository;
    @Autowired
    private CancellationService cancellationService;
    @Autowired
    private ReturnService returnService;
    @Autowired
    private RefundService refundService;
    @Autowired
    private ClaimService claimService;

    private Customer customer;

    @BeforeEach
    void setUp() {
        customer = customerRepository.save(new Customer("Test", "User", unique() + "@example.pk", "+9230012" + shortNum()));
    }

    private Order newOrder(BigDecimal amount) {
        Order order = new Order("ORD-" + shortId(), customer, "PKR", amount, BigDecimal.ZERO, amount, Instant.now());
        order.addItem(new OrderItem("Test Product", "SKU-" + shortId(), 2, amount.divide(BigDecimal.valueOf(2)), true, false));
        return orderRepository.save(order);
    }

    private VerifiedOrderRef refFor(Order order) {
        return new VerifiedOrderRef(order.getId(), order.getOrderNumber(), customer.getId(), IdentityAssurance.PHONE_MATCHED, Instant.now());
    }

    private String unique() {
        return "user." + UUID.randomUUID().toString().substring(0, 8);
    }

    private String shortNum() {
        return String.valueOf(System.nanoTime() % 10_000);
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    // ---- Cancellation ----

    @Test
    void cancellingCodUnfulfilledOrderRequiresNoRefund() {
        Order order = newOrder(BigDecimal.valueOf(1000));
        Payment payment = paymentRepository.save(new Payment(order, PaymentMethod.COD, order.getTotalAmount(), "PKR", PaymentStatus.PENDING));

        var result = cancellationService.cancel(refFor(order));

        assertThat(result.paymentConsequence()).isEqualTo(PaymentConsequence.NO_REFUND_REQUIRED);
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getOrderStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.VOIDED);
        assertThat(refundRepository.findByOrderId(order.getId())).isEmpty();
    }

    @Test
    void cancellingPaidUnfulfilledOrderCreatesPendingRefundForFullAmount() {
        Order order = newOrder(BigDecimal.valueOf(2000));
        paymentRepository.save(new Payment(order, PaymentMethod.CARD, order.getTotalAmount(), "PKR", PaymentStatus.PAID));

        var result = cancellationService.cancel(refFor(order));

        assertThat(result.paymentConsequence()).isEqualTo(PaymentConsequence.REFUND_REQUIRED);
        assertThat(result.refund()).isNotNull();
        assertThat(result.refund().getStatus()).isEqualTo(RefundStatus.PENDING);
        assertThat(result.refund().getAmount()).isEqualByComparingTo(order.getTotalAmount());
    }

    @Test
    void cancellingFulfilledOrderIsDenied() {
        Order order = newOrder(BigDecimal.valueOf(500));
        paymentRepository.save(new Payment(order, PaymentMethod.CARD, order.getTotalAmount(), "PKR", PaymentStatus.PAID));
        shipmentRepository.save(new Shipment(order, "TCS", "TCS-1", ShipmentStatus.IN_TRANSIT));
        order.setFulfillmentStatus(com.voxticket.persistence.entity.enums.FulfillmentStatus.FULFILLED);
        orderRepository.save(order);

        assertThatThrownBy(() -> cancellationService.cancel(refFor(order)))
                .isInstanceOf(CancellationNotEligibleException.class)
                .extracting(ex -> ((CancellationNotEligibleException) ex).getReason())
                .isEqualTo(CancellationDenialReason.ORDER_FULFILLED);
    }

    @Test
    void cancellingAlreadyCancelledOrderIsDeniedOnTheSecondAttempt() {
        Order order = newOrder(BigDecimal.valueOf(500));
        paymentRepository.save(new Payment(order, PaymentMethod.COD, order.getTotalAmount(), "PKR", PaymentStatus.PENDING));

        cancellationService.cancel(refFor(order));

        assertThatThrownBy(() -> cancellationService.cancel(refFor(order)))
                .isInstanceOf(CancellationNotEligibleException.class)
                .extracting(ex -> ((CancellationNotEligibleException) ex).getReason())
                .isEqualTo(CancellationDenialReason.ALREADY_CANCELLED);
    }

    // ---- Return ----

    private Order deliveredOrder(BigDecimal amount, long deliveredDaysAgo) {
        Order order = newOrder(amount);
        paymentRepository.save(new Payment(order, PaymentMethod.CARD, order.getTotalAmount(), "PKR", PaymentStatus.PAID));
        // FIX: construct fully (including deliveredAt) BEFORE the single save() call,
        // rather than saving first and mutating a since-detached instance afterward.
        Shipment shipment = new Shipment(order, "TCS", "TCS-" + shortId(), ShipmentStatus.DELIVERED);
        shipment.setDeliveredAt(Instant.now().minus(deliveredDaysAgo, ChronoUnit.DAYS));
        shipmentRepository.save(shipment);
        return order;
    }

    @Test
    void requestingReturnOnEligibleItemSucceeds() {
        Order order = deliveredOrder(BigDecimal.valueOf(1000), 5);
        String sku = order.getItems().get(0).getSku();

        ReturnRequest returnRequest = returnService.requestReturn(refFor(order), sku, 1, ReturnReason.WRONG_SIZE);

        assertThat(returnRequest.getStatus()).isEqualTo(ReturnStatus.REQUESTED);
        assertThat(returnRequest.getItems()).hasSize(1);
        assertThat(returnRequest.getItems().get(0).getQuantity()).isEqualTo(1);
    }

    @Test
    void requestingReturnBeyondEligibleQuantityIsDenied() {
        Order order = deliveredOrder(BigDecimal.valueOf(1000), 5);
        String sku = order.getItems().get(0).getSku();

        assertThatThrownBy(() -> returnService.requestReturn(refFor(order), sku, 5, ReturnReason.WRONG_SIZE))
                .isInstanceOf(ReturnNotEligibleException.class)
                .extracting(ex -> ((ReturnNotEligibleException) ex).getReason())
                .isEqualTo(ReturnDenialReason.NO_REMAINING_RETURNABLE_QUANTITY);
    }

    @Test
    void fullReturnLifecycleApprovedAtInspectionCreatesRefund() {
        Order order = deliveredOrder(BigDecimal.valueOf(1000), 5);
        String sku = order.getItems().get(0).getSku();
        ReturnRequest returnRequest = returnService.requestReturn(refFor(order), sku, 1, ReturnReason.DEFECTIVE);

        returnService.approve(returnRequest.getId());
        returnService.markInTransit(returnRequest.getId());
        returnService.markReceived(returnRequest.getId());
        ReturnRequest completed = returnService.completeInspection(returnRequest.getId(), true);

        assertThat(completed.getStatus()).isEqualTo(ReturnStatus.COMPLETED);
        var refunds = refundRepository.findByOrderId(order.getId());
        assertThat(refunds).hasSize(1);
        assertThat(refunds.get(0).getReason()).isEqualTo(RefundReason.RETURN);
        assertThat(refunds.get(0).getAmount()).isEqualByComparingTo(BigDecimal.valueOf(500)); // 1 unit at half the order total
    }

    @Test
    void fullReturnLifecycleRejectedAtInspectionCreatesNoRefund() {
        Order order = deliveredOrder(BigDecimal.valueOf(1000), 5);
        String sku = order.getItems().get(0).getSku();
        ReturnRequest returnRequest = returnService.requestReturn(refFor(order), sku, 1, ReturnReason.DEFECTIVE);

        returnService.approve(returnRequest.getId());
        returnService.markReceived(returnRequest.getId());
        ReturnRequest rejected = returnService.completeInspection(returnRequest.getId(), false);

        assertThat(rejected.getStatus()).isEqualTo(ReturnStatus.REJECTED);
        assertThat(refundRepository.findByOrderId(order.getId())).isEmpty();
    }

    // ---- Refund ----

    @Test
    void refundSucceedingForFullAmountMarksPaymentFullyRefunded() {
        Order order = newOrder(BigDecimal.valueOf(1000));
        Payment payment = paymentRepository.save(new Payment(order, PaymentMethod.CARD, order.getTotalAmount(), "PKR", PaymentStatus.PAID));
        Refund refund = refundService.initiateRefund(order, payment, null, order.getTotalAmount(), RefundReason.CANCELLATION);

        refundService.markSucceeded(refund.getId());

        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void refundSucceedingForPartialAmountMarksPaymentPartiallyRefunded() {
        Order order = newOrder(BigDecimal.valueOf(1000));
        Payment payment = paymentRepository.save(new Payment(order, PaymentMethod.CARD, order.getTotalAmount(), "PKR", PaymentStatus.PAID));
        Refund refund = refundService.initiateRefund(order, payment, null, BigDecimal.valueOf(400), RefundReason.RETURN);

        refundService.markSucceeded(refund.getId());

        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
    }

    @Test
    void refundFailingLeavesPaymentStatusUnchanged() {
        Order order = newOrder(BigDecimal.valueOf(1000));
        Payment payment = paymentRepository.save(new Payment(order, PaymentMethod.CARD, order.getTotalAmount(), "PKR", PaymentStatus.PAID));
        Refund refund = refundService.initiateRefund(order, payment, null, order.getTotalAmount(), RefundReason.CANCELLATION);

        Refund failed = refundService.markFailed(refund.getId());

        assertThat(failed.getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(failed.getFailedAt()).isNotNull();
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @Test
    void reprocessingATerminalRefundIsRejected() {
        Order order = newOrder(BigDecimal.valueOf(1000));
        Payment payment = paymentRepository.save(new Payment(order, PaymentMethod.CARD, order.getTotalAmount(), "PKR", PaymentStatus.PAID));
        Refund refund = refundService.initiateRefund(order, payment, null, order.getTotalAmount(), RefundReason.CANCELLATION);
        refundService.markSucceeded(refund.getId());

        assertThatThrownBy(() -> refundService.markSucceeded(refund.getId())).isInstanceOf(IllegalStateException.class);
    }

    // ---- Claim ----

    @Test
    void filingClaimCreatesLinkedTicketAndClaim() {
        Order order = deliveredOrder(BigDecimal.valueOf(1000), 3);
        String sku = order.getItems().get(0).getSku();

        var claim = claimService.fileClaim(refFor(order), sku, ClaimReason.DAMAGED, ClaimResolution.REPLACEMENT, "Item arrived broken.");

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.OPEN);
        assertThat(claim.getSupportTicket()).isNotNull();
        assertThat(claim.getSupportTicket().getCategory()).isEqualTo(com.voxticket.persistence.entity.enums.TicketCategory.CLAIM);
    }

    @Test
    void filingClaimAgainstCancelledOrderIsRejected() {
        Order order = newOrder(BigDecimal.valueOf(500));
        paymentRepository.save(new Payment(order, PaymentMethod.COD, order.getTotalAmount(), "PKR", PaymentStatus.PENDING));
        String sku = order.getItems().get(0).getSku();
        cancellationService.cancel(refFor(order));

        assertThatThrownBy(() -> claimService.fileClaim(refFor(order), sku, ClaimReason.DAMAGED, ClaimResolution.REFUND, "test"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void resolvingClaimWithRefundCreatesARefund() {
        Order order = deliveredOrder(BigDecimal.valueOf(1000), 3);
        String sku = order.getItems().get(0).getSku();
        var claim = claimService.fileClaim(refFor(order), sku, ClaimReason.MISSING_ITEM, ClaimResolution.REFUND, "Missing one unit.");

        var resolved = claimService.resolveWithRefund(claim.getId(), BigDecimal.valueOf(500));

        assertThat(resolved.getStatus()).isEqualTo(ClaimStatus.RESOLVED);
        var refunds = refundRepository.findByOrderId(order.getId());
        assertThat(refunds).hasSize(1);
        assertThat(refunds.get(0).getReason()).isEqualTo(RefundReason.CLAIM);
    }
}