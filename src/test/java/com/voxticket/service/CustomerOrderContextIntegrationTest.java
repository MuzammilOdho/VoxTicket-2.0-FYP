package com.voxticket.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.json.JsonMapper;
import com.voxticket.identity.CustomerIdentity;
import com.voxticket.identity.IdentityAssurance;
import com.voxticket.identity.ResourceNotFoundForAccountException;
import com.voxticket.persistence.entity.Customer;
import com.voxticket.persistence.entity.Order;
import com.voxticket.persistence.entity.OrderClaim;
import com.voxticket.persistence.entity.OrderItem;
import com.voxticket.persistence.entity.Payment;
import com.voxticket.persistence.entity.Refund;
import com.voxticket.persistence.entity.ReturnItem;
import com.voxticket.persistence.entity.ReturnRequest;
import com.voxticket.persistence.entity.Shipment;
import com.voxticket.persistence.entity.enums.ClaimReason;
import com.voxticket.persistence.entity.enums.ClaimResolution;
import com.voxticket.persistence.entity.enums.ClaimStatus;
import com.voxticket.persistence.entity.enums.FulfillmentStatus;
import com.voxticket.persistence.entity.enums.OrderStatus;
import com.voxticket.persistence.entity.enums.PaymentMethod;
import com.voxticket.persistence.entity.enums.PaymentStatus;
import com.voxticket.persistence.entity.enums.RefundReason;
import com.voxticket.persistence.entity.enums.RefundStatus;
import com.voxticket.persistence.entity.enums.ReturnReason;
import com.voxticket.persistence.entity.enums.ReturnStatus;
import com.voxticket.persistence.entity.enums.ShipmentStatus;
import com.voxticket.persistence.repository.CustomerRepository;
import com.voxticket.persistence.repository.OrderClaimRepository;
import com.voxticket.persistence.repository.OrderRepository;
import com.voxticket.persistence.repository.PaymentRepository;
import com.voxticket.persistence.repository.RefundRepository;
import com.voxticket.persistence.repository.ReturnRequestRepository;
import com.voxticket.persistence.repository.ShipmentRepository;
import com.voxticket.service.dto.OrderContextView;
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

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
@Transactional
class CustomerOrderContextIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @Autowired
    private CustomerOrderQueryService queryService;
    @Autowired
    private JsonMapper objectMapper;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private ShipmentRepository shipmentRepository;
    @Autowired
    private ReturnRequestRepository returnRequestRepository;
    @Autowired
    private RefundRepository refundRepository;
    @Autowired
    private OrderClaimRepository orderClaimRepository;

    private Customer customer;
    private CustomerIdentity identity;

    @BeforeEach
    void setUp() {
        customer = customerRepository.save(new Customer("Test", "User", "ctx." + UUID.randomUUID() + "@example.pk", "+9230017" + (System.nanoTime() % 100_000)));
        identity = new CustomerIdentity(customer.getId(), IdentityAssurance.PHONE_MATCHED, customer.getPhone());
    }

    private Order newOrder(BigDecimal amount, OrderItem... items) {
        Order order = new Order("ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), customer, "PKR", amount, BigDecimal.ZERO, amount, Instant.now());
        if (items.length == 0) {
            order.addItem(new OrderItem("Test Product", "SKU-" + UUID.randomUUID().toString().substring(0, 8), 1, amount, true, false));
        } else {
            for (OrderItem item : items) {
                order.addItem(item);
            }
        }
        return orderRepository.save(order);
    }

    private OrderItem item(String name, int quantity, boolean returnable, boolean finalSale) {
        return new OrderItem(name, "SKU-" + UUID.randomUUID().toString().substring(0, 8), quantity, BigDecimal.valueOf(500), returnable, finalSale);
    }

    private Shipment deliveredShipment(Order order, Instant deliveredAt) {
        Shipment shipment = new Shipment(order, "TCS", "TCS-" + UUID.randomUUID().toString().substring(0, 6), ShipmentStatus.DELIVERED);
        shipment.setDeliveredAt(deliveredAt);
        return shipmentRepository.save(shipment);
    }

    @Test
    void orderContextUsesStableLanguageNeutralStatusCodes() {
        Order order = newOrder(BigDecimal.valueOf(1000));
        paymentRepository.save(new Payment(order, PaymentMethod.CARD, order.getTotalAmount(), "PKR", PaymentStatus.AUTHORIZED));

        var context = queryService.getOrderContext(identity, order.getOrderNumber());

        assertThat(context.orderStatus()).isEqualTo(OrderStatus.OPEN);
        assertThat(context.fulfillmentStatus()).isEqualTo(FulfillmentStatus.UNFULFILLED);
        assertThat(context.payment().method()).isEqualTo(PaymentMethod.CARD);
        assertThat(context.payment().status()).isEqualTo(PaymentStatus.AUTHORIZED);
    }

    @Test
    void codOrderShowsZeroAmountCollected() {
        Order order = newOrder(BigDecimal.valueOf(500));
        paymentRepository.save(new Payment(order, PaymentMethod.COD, order.getTotalAmount(), "PKR", PaymentStatus.PENDING));

        var context = queryService.getOrderContext(identity, order.getOrderNumber());

        assertThat(context.payment().amountCollected()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void paidOrderShowsFullAmountCollected() {
        Order order = newOrder(BigDecimal.valueOf(500));
        paymentRepository.save(new Payment(order, PaymentMethod.CARD, order.getTotalAmount(), "PKR", PaymentStatus.PAID));

        var context = queryService.getOrderContext(identity, order.getOrderNumber());

        assertThat(context.payment().amountCollected()).isEqualByComparingTo(BigDecimal.valueOf(500));
    }

    @Test
    void modelFacingItemsExposeNoSkuOrInternalIdentifiers() throws Exception {
        Order order = newOrder(BigDecimal.valueOf(500));
        paymentRepository.save(new Payment(order, PaymentMethod.CARD, order.getTotalAmount(), "PKR", PaymentStatus.PAID));

        var context = queryService.getOrderContext(identity, order.getOrderNumber());
        String json = objectMapper.writeValueAsString(context);

        assertThat(context.items()).hasSize(1);
        assertThat(context.items().get(0).productName()).isEqualTo("Test Product");
        assertThat(json).doesNotContain("\"sku\"");
        assertThat(json).doesNotContain("SKU-");
        assertThat(json).contains("\"productName\"").contains("\"returnEligibility\"");
    }

    @Test
    void noInternalDatabaseOrCustomerIdentifiersAppearInTheModelFacingAggregate() throws Exception {
        Order order = newOrder(BigDecimal.valueOf(500));
        Payment payment = paymentRepository.save(new Payment(order, PaymentMethod.CARD, order.getTotalAmount(), "PKR", PaymentStatus.PAID));

        var context = queryService.getOrderContext(identity, order.getOrderNumber());
        String json = objectMapper.writeValueAsString(context);

        assertThat(json).doesNotContain(customer.getId().toString());
        assertThat(json).doesNotContain(order.getId().toString());
        assertThat(json).doesNotContain(payment.getId().toString());
        assertThat(json).doesNotContain("\"customerId\"");
    }

    @Test
    void deliveredEligibleItemGetsDeterministicReturnEligibilityFromJava() {
        Order order = newOrder(BigDecimal.valueOf(1000), item("Running Shoes", 2, true, false));
        deliveredShipment(order, Instant.now());

        var context = queryService.getOrderContext(identity, order.getOrderNumber());

        var eligibility = context.items().get(0).returnEligibility();
        assertThat(eligibility.eligible()).isTrue();
        assertThat(eligibility.denialReason()).isNull();
        assertThat(eligibility.maxReturnableQuantity()).isEqualTo(2);
    }

    @Test
    void undeliveredItemIsDeniedWithoutModelInference() {
        Order order = newOrder(BigDecimal.valueOf(500));

        var context = queryService.getOrderContext(identity, order.getOrderNumber());

        var eligibility = context.items().get(0).returnEligibility();
        assertThat(eligibility.eligible()).isFalse();
        assertThat(eligibility.denialReason()).isEqualTo("ITEM_NOT_DELIVERED");
        assertThat(eligibility.maxReturnableQuantity()).isZero();
    }

    @Test
    void finalSaleItemIsDeniedWithAStableCode() {
        Order order = newOrder(BigDecimal.valueOf(500), item("Clearance Jacket", 1, true, true));
        deliveredShipment(order, Instant.now());

        var context = queryService.getOrderContext(identity, order.getOrderNumber());

        var eligibility = context.items().get(0).returnEligibility();
        assertThat(eligibility.eligible()).isFalse();
        assertThat(eligibility.denialReason()).isEqualTo("ITEM_FINAL_SALE");
        assertThat(eligibility.maxReturnableQuantity()).isZero();
    }

    @Test
    void outsideWindowItemIsDeniedWithAStableCode() {
        Order order = newOrder(BigDecimal.valueOf(500));
        deliveredShipment(order, Instant.now().minus(31, ChronoUnit.DAYS));

        var context = queryService.getOrderContext(identity, order.getOrderNumber());

        var eligibility = context.items().get(0).returnEligibility();
        assertThat(eligibility.eligible()).isFalse();
        assertThat(eligibility.denialReason()).isEqualTo("RETURN_WINDOW_EXPIRED");
        assertThat(eligibility.maxReturnableQuantity()).isZero();
    }

    @Test
    void alreadyReturnedQuantityReducesMaxReturnableQuantity() {
        Order order = newOrder(BigDecimal.valueOf(1000), item("Running Shoes", 2, true, false));
        deliveredShipment(order, Instant.now());
        OrderItem orderItem = order.getItems().get(0);
        ReturnRequest returnRequest = new ReturnRequest("RET-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), order, ReturnReason.WRONG_SIZE);
        returnRequest.addItem(new ReturnItem(orderItem, 1, ReturnReason.WRONG_SIZE));
        returnRequestRepository.save(returnRequest);

        var context = queryService.getOrderContext(identity, order.getOrderNumber());

        var eligibility = context.items().get(0).returnEligibility();
        assertThat(eligibility.eligible()).isTrue();
        assertThat(eligibility.denialReason()).isNull();
        assertThat(eligibility.maxReturnableQuantity()).isEqualTo(1);
    }

    @Test
    void shipmentReturnRefundAndClaimHistoriesUseStableCodes() {
        Order order = newOrder(BigDecimal.valueOf(500));
        Payment payment = paymentRepository.save(new Payment(order, PaymentMethod.CARD, order.getTotalAmount(), "PKR", PaymentStatus.PAID));
        Shipment shipment = shipmentRepository.save(new Shipment(order, "TCS", "TCS-TRACK-1", ShipmentStatus.IN_TRANSIT));
        ReturnRequest returnRequest = returnRequestRepository.save(
                new ReturnRequest("RET-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), order, ReturnReason.WRONG_SIZE));
        Refund refund = new Refund("RFN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), order, payment, returnRequest, BigDecimal.valueOf(500), RefundReason.RETURN);
        refund.setStatus(RefundStatus.FAILED);
        refund.setFailedAt(Instant.now());
        refundRepository.save(refund);
        OrderClaim claim = new OrderClaim("CLM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), order, order.getItems().get(0), ClaimReason.DAMAGED, ClaimResolution.REPLACEMENT);
        claim.setStatus(ClaimStatus.IN_REVIEW);
        orderClaimRepository.save(claim);

        var context = queryService.getOrderContext(identity, order.getOrderNumber());

        assertThat(context.shipments()).hasSize(1);
        assertThat(context.shipments().get(0).status()).isEqualTo(ShipmentStatus.IN_TRANSIT);
        assertThat(context.shipments().get(0).trackingNumber()).isEqualTo("TCS-TRACK-1");
        assertThat(context.returns()).hasSize(1);
        assertThat(context.returns().get(0).status()).isEqualTo(ReturnStatus.REQUESTED);
        assertThat(context.returns().get(0).reason()).isEqualTo("WRONG_SIZE");
        assertThat(context.refunds()).hasSize(1);
        assertThat(context.refunds().get(0).status()).isEqualTo(RefundStatus.FAILED);
        assertThat(context.refunds().get(0).failedAt()).isNotNull();
        assertThat(context.claims()).hasSize(1);
        assertThat(context.claims().get(0).status()).isEqualTo(ClaimStatus.IN_REVIEW);
        assertThat(context.claims().get(0).reason()).isEqualTo("DAMAGED");
        assertThat(context.claims().get(0).requestedResolution()).isEqualTo("REPLACEMENT");
    }

    @Test
    void failedRefundExposesFailedAtButNoInventedCause() throws Exception {
        Order order = newOrder(BigDecimal.valueOf(500));
        Payment payment = paymentRepository.save(new Payment(order, PaymentMethod.CARD, order.getTotalAmount(), "PKR", PaymentStatus.PAID));
        ReturnRequest returnRequest = returnRequestRepository.save(
                new ReturnRequest("RET-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), order, ReturnReason.DEFECTIVE));
        Refund refund = new Refund("RFN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), order, payment, returnRequest, BigDecimal.valueOf(500), RefundReason.RETURN);
        refund.setStatus(RefundStatus.FAILED);
        Instant failedAt = Instant.now();
        refund.setFailedAt(failedAt);
        refundRepository.save(refund);

        OrderContextView context = queryService.getOrderContext(identity, order.getOrderNumber());
        String json = objectMapper.writeValueAsString(context);
        var refundsNode = objectMapper.readTree(json).get("refunds");

        assertThat(context.refunds()).hasSize(1);
        assertThat(context.refunds().get(0).status()).isEqualTo(RefundStatus.FAILED);
        assertThat(context.refunds().get(0).failedAt()).isNotNull();
        assertThat(refundsNode.get(0).get("status").asText()).isEqualTo("FAILED");
        assertThat(refundsNode.get(0).has("failedAt")).isTrue();
        assertThat(json).doesNotContain("failureReason").doesNotContain("failureCause").doesNotContain("errorMessage");
    }

    @Test
    void cancellationEligibilityIsStructuredNotProse() {
        Order order = newOrder(BigDecimal.valueOf(500));
        paymentRepository.save(new Payment(order, PaymentMethod.CARD, order.getTotalAmount(), "PKR", PaymentStatus.PAID));

        var context = queryService.getOrderContext(identity, order.getOrderNumber());

        assertThat(context.cancellationEligibility().eligible()).isTrue();
        assertThat(context.cancellationEligibility().denialReason()).isNull();
        assertThat(context.cancellationEligibility().paymentConsequence()).isEqualTo("REFUND_REQUIRED");
    }

    @Test
    void shippedOrderReportsStructuredCancellationDenial() {
        Order order = newOrder(BigDecimal.valueOf(500));
        paymentRepository.save(new Payment(order, PaymentMethod.CARD, order.getTotalAmount(), "PKR", PaymentStatus.PAID));
        Shipment shipment = new Shipment(order, "TCS", "TCS-1", ShipmentStatus.IN_TRANSIT);
        shipmentRepository.save(shipment);
        order.setFulfillmentStatus(FulfillmentStatus.FULFILLED);
        orderRepository.save(order);

        var context = queryService.getOrderContext(identity, order.getOrderNumber());

        assertThat(context.cancellationEligibility().eligible()).isFalse();
        assertThat(context.cancellationEligibility().denialReason()).isEqualTo("ORDER_FULFILLED");
        assertThat(context.cancellationEligibility().paymentConsequence()).isNull();
    }

    @Test
    void anotherCustomersOrderIsStillNotFoundForAccountViaTheAggregateContext() {
        Customer otherCustomer = customerRepository.save(new Customer("Other", "User", "other." + UUID.randomUUID() + "@example.pk", "+9230018" + (System.nanoTime() % 100_000)));
        Order theirOrder = new Order("ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), otherCustomer, "PKR", BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, Instant.now());
        orderRepository.save(theirOrder);

        assertThatThrownBy(() -> queryService.getOrderContext(identity, theirOrder.getOrderNumber()))
                .isInstanceOf(ResourceNotFoundForAccountException.class);
    }
}
