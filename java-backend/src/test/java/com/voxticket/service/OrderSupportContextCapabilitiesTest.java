package com.voxticket.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.voxticket.identity.CustomerIdentity;
import com.voxticket.identity.IdentityAssurance;
import com.voxticket.identity.OwnedOrderResolver;
import com.voxticket.identity.VerifiedOrderRef;
import com.voxticket.persistence.entity.BaseEntity;
import com.voxticket.persistence.entity.Customer;
import com.voxticket.persistence.entity.Order;
import com.voxticket.persistence.entity.OrderClaim;
import com.voxticket.persistence.entity.OrderItem;
import com.voxticket.persistence.entity.Payment;
import com.voxticket.persistence.entity.Refund;
import com.voxticket.persistence.entity.ReturnItem;
import com.voxticket.persistence.entity.ReturnRequest;
import com.voxticket.persistence.entity.Shipment;
import com.voxticket.persistence.entity.SupportTicket;
import com.voxticket.persistence.entity.enums.ClaimReason;
import com.voxticket.persistence.entity.enums.ClaimResolution;
import com.voxticket.persistence.entity.enums.ClaimStatus;
import com.voxticket.persistence.entity.enums.FulfillmentStatus;
import com.voxticket.persistence.entity.enums.ItemCondition;
import com.voxticket.persistence.entity.enums.OrderStatus;
import com.voxticket.persistence.entity.enums.PaymentMethod;
import com.voxticket.persistence.entity.enums.PaymentStatus;
import com.voxticket.persistence.entity.enums.RefundReason;
import com.voxticket.persistence.entity.enums.RefundStatus;
import com.voxticket.persistence.entity.enums.ReturnReason;
import com.voxticket.persistence.entity.enums.ShipmentStatus;
import com.voxticket.persistence.entity.enums.TicketCategory;
import com.voxticket.persistence.entity.enums.TicketPriority;
import com.voxticket.persistence.entity.enums.TicketStatus;
import com.voxticket.persistence.repository.OrderClaimRepository;
import com.voxticket.persistence.repository.OrderRepository;
import com.voxticket.persistence.repository.PaymentRepository;
import com.voxticket.persistence.repository.RefundRepository;
import com.voxticket.persistence.repository.ReturnItemRepository;
import com.voxticket.persistence.repository.ReturnRequestRepository;
import com.voxticket.persistence.repository.ShipmentRepository;
import com.voxticket.persistence.repository.SupportTicketRepository;
import com.voxticket.policy.CancellationPolicyService;
import com.voxticket.policy.ClaimCapabilityState;
import com.voxticket.policy.ClaimPolicyService;
import com.voxticket.policy.ReturnPolicyService;
import com.voxticket.service.dto.OrderSupportContext;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Capability regression scenarios for the authoritative support-state read.
 * Pure Mockito unit tests (no Spring, no database) so they run anywhere.
 * Every scenario asserts that Java - not the LLM - decides what support
 * actions are available, and that existing returns/claims/refunds are
 * reflected so duplicate actions are never implied.
 */
class OrderSupportContextCapabilitiesTest {

    private final OwnedOrderResolver ownedOrderResolver = mock(OwnedOrderResolver.class);
    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final ShipmentRepository shipmentRepository = mock(ShipmentRepository.class);
    private final ReturnRequestRepository returnRequestRepository = mock(ReturnRequestRepository.class);
    private final RefundRepository refundRepository = mock(RefundRepository.class);
    private final SupportTicketRepository supportTicketRepository = mock(SupportTicketRepository.class);
    private final OrderClaimRepository orderClaimRepository = mock(OrderClaimRepository.class);
    private final ReturnItemRepository returnItemRepository = mock(ReturnItemRepository.class);

    private final ReturnPolicyService returnPolicyService =
            new ReturnPolicyService(shipmentRepository, returnItemRepository, 30);
    private final CancellationPolicyService cancellationPolicyService = new CancellationPolicyService();
    private final ClaimPolicyService claimPolicyService = new ClaimPolicyService();

    private final CustomerOrderQueryService queryService = new CustomerOrderQueryService(
            ownedOrderResolver, orderRepository, paymentRepository,
            shipmentRepository, returnRequestRepository, refundRepository, supportTicketRepository,
            orderClaimRepository, cancellationPolicyService, returnPolicyService, claimPolicyService);

    private int orderSeq;

    /** Mirrors persisted entities: production item IDs are never null. */
    private static void assignId(BaseEntity entity, UUID id) {
        try {
            Field field = BaseEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private class Fixture {
        final UUID customerId = UUID.randomUUID();
        final CustomerIdentity identity =
                new CustomerIdentity(customerId, IdentityAssurance.PHONE_MATCHED, "+923001234567");
        final Customer customer = new Customer("Test", "User",
                "cap." + UUID.randomUUID() + "@example.pk", "+923001234567");
        final Order order;
        Payment payment;
        final List<Shipment> shipments = new ArrayList<>();
        final List<ReturnRequest> returns = new ArrayList<>();
        final List<ReturnItem> returnItems = new ArrayList<>();
        final List<Refund> refunds = new ArrayList<>();
        final List<OrderClaim> claims = new ArrayList<>();

        Fixture(BigDecimal total, OrderItem... items) {
            order = new Order("ORD-" + (++orderSeq) + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(),
                    customer, "PKR", total, BigDecimal.ZERO, total, Instant.now());
            assignId(order, UUID.randomUUID());
            if (items.length == 0) {
                order.addItem(item("Test Product", 1, true, false));
            } else {
                for (OrderItem item : items) {
                    order.addItem(item);
                }
            }
        }

        Fixture paid(PaymentMethod method, PaymentStatus status) {
            payment = new Payment(order, method, order.getTotalAmount(), "PKR", status);
            assignId(payment, UUID.randomUUID());
            return this;
        }

        Fixture fulfilled(FulfillmentStatus fulfillmentStatus) {
            order.setFulfillmentStatus(fulfillmentStatus);
            return this;
        }

        Fixture cancelled() {
            order.setOrderStatus(OrderStatus.CANCELLED);
            return this;
        }

        Fixture deliveredShipment(Instant deliveredAt) {
            Shipment shipment = new Shipment(order, "TCS", "TRACK-" + UUID.randomUUID().toString().substring(0, 6),
                    ShipmentStatus.DELIVERED);
            shipment.setDeliveredAt(deliveredAt);
            shipments.add(shipment);
            return fulfilled(FulfillmentStatus.FULFILLED);
        }

        Fixture lostShipment() {
            shipments.add(new Shipment(order, "TCS", "TRACK-" + UUID.randomUUID().toString().substring(0, 6),
                    ShipmentStatus.LOST));
            return fulfilled(FulfillmentStatus.FULFILLED);
        }

        Fixture returnOf(OrderItem item, int quantity) {
            ReturnRequest request = new ReturnRequest(
                    "RET-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(), order, ReturnReason.WRONG_SIZE);
            ReturnItem returnItem = new ReturnItem(item, quantity, ReturnReason.WRONG_SIZE);
            request.addItem(returnItem);
            returns.add(request);
            returnItems.add(returnItem);
            return this;
        }

        Fixture failedRefund() {
            Refund refund = new Refund("RFN-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(),
                    order, payment, returns.isEmpty() ? null : returns.get(0), BigDecimal.valueOf(500), RefundReason.RETURN);
            refund.setStatus(RefundStatus.FAILED);
            refund.setFailedAt(Instant.now());
            refunds.add(refund);
            return this;
        }

        Fixture cancellationRefund() {
            Refund refund = new Refund("RFN-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(),
                    order, payment, null, BigDecimal.valueOf(500), RefundReason.CANCELLATION);
            refund.setStatus(RefundStatus.SUCCEEDED);
            refund.setCompletedAt(Instant.now());
            refunds.add(refund);
            return this;
        }

        Fixture claim(OrderItem item, ClaimStatus status) {
            OrderClaim claim = new OrderClaim("CLM-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(),
                    order, item, ClaimReason.DAMAGED, ClaimResolution.MANUAL_REVIEW);
            claim.setStatus(status);
            claims.add(claim);
            return this;
        }

        OrderSupportContext context() {
            String orderNumber = order.getOrderNumber();
            UUID orderId = UUID.randomUUID();
            when(ownedOrderResolver.resolve(identity, orderNumber)).thenReturn(
                    new VerifiedOrderRef(orderId, orderNumber, customerId, IdentityAssurance.PHONE_MATCHED, Instant.now()));
            when(orderRepository.findByIdWithItems(orderId)).thenReturn(Optional.of(order));
            when(paymentRepository.findFirstByOrderIdOrderByCreatedAtDesc(orderId))
                    .thenReturn(Optional.ofNullable(payment));
            // The bulk policy path uses findByOrderId/findByOrderItemIdIn; the
            // verified ref id differs from the transient order id - any() covers both.
            // Read methods use the fetch-join variants so no lazy N+1 chain remains.
            when(shipmentRepository.findByOrderId(any())).thenReturn(shipments);
            when(returnRequestRepository.findByOrderIdWithItems(any())).thenReturn(returns);
            when(refundRepository.findByOrderIdWithDetails(any())).thenReturn(refunds);
            when(orderClaimRepository.findByOrderIdWithDetails(any())).thenReturn(claims);
            when(returnItemRepository.findByOrderItemIdIn(any())).thenReturn(returnItems);
            return queryService.getOrderSupportContext(identity, orderNumber);
        }
    }

    private OrderItem item(String name, int quantity, boolean returnable, boolean finalSale) {
        OrderItem item = new OrderItem(name, "SKU-" + UUID.randomUUID().toString().substring(0, 8), quantity,
                BigDecimal.valueOf(500), returnable, finalSale);
        assignId(item, UUID.randomUUID());
        return item;
    }

    // ---- Scenario 1: fulfilled + lost shipment ----

    @Test
    void lostShipmentMakesCancellationReturnUnavailableAndClaimAvailable() {
        Fixture f = new Fixture(BigDecimal.valueOf(500))
                .paid(PaymentMethod.CARD, PaymentStatus.PAID)
                .lostShipment();

        OrderSupportContext context = f.context();

        assertThat(context.cancellation().available()).isFalse();
        assertThat(context.cancellation().denialReason()).isEqualTo("ORDER_FULFILLED");
        var returnCapability = context.items().get(0).returnCapability();
        assertThat(returnCapability.available()).isFalse();
        assertThat(returnCapability.denialReason()).isEqualTo("ITEM_NOT_DELIVERED");
        var claimCapability = context.items().get(0).claimCapability();
        assertThat(claimCapability.state()).isEqualTo(ClaimCapabilityState.AVAILABLE);
        assertThat(claimCapability.denialReason()).isNull();
        assertThat(claimCapability.resolution()).isEqualTo("MANUAL_REVIEW");
        assertThat(claimCapability.existingClaim()).isNull();
        assertThat(context.supportCapabilities().anyReturnAvailable()).isFalse();
        assertThat(context.supportCapabilities().anyClaimAvailable()).isTrue();
        assertThat(context.shipments()).hasSize(1);
        assertThat(context.shipments().get(0).status()).isEqualTo(ShipmentStatus.LOST);
    }

    // ---- Scenario 2: delivered within return window ----

    @Test
    void deliveredItemWithinWindowIsReturnAvailableWithFullQuantity() {
        Fixture f = new Fixture(BigDecimal.valueOf(1000), item("Running Shoes", 2, true, false))
                .paid(PaymentMethod.CARD, PaymentStatus.PAID)
                .deliveredShipment(Instant.now());

        OrderSupportContext context = f.context();

        var returnCapability = context.items().get(0).returnCapability();
        assertThat(returnCapability.available()).isTrue();
        assertThat(returnCapability.denialReason()).isNull();
        assertThat(returnCapability.maxReturnableQuantity()).isEqualTo(2);
        assertThat(context.supportCapabilities().anyReturnAvailable()).isTrue();
    }

    // ---- Scenario 3: delivered outside return window ----

    @Test
    void deliveredItemOutsideWindowIsReturnDeniedWithStableCode() {
        Fixture f = new Fixture(BigDecimal.valueOf(500))
                .paid(PaymentMethod.CARD, PaymentStatus.PAID)
                .deliveredShipment(Instant.now().minus(31, ChronoUnit.DAYS));

        OrderSupportContext context = f.context();

        var returnCapability = context.items().get(0).returnCapability();
        assertThat(returnCapability.available()).isFalse();
        assertThat(returnCapability.denialReason()).isEqualTo("RETURN_WINDOW_EXPIRED");
        assertThat(returnCapability.maxReturnableQuantity()).isZero();
        assertThat(context.supportCapabilities().anyReturnAvailable()).isFalse();
        // Claim capability is orthogonal to the return window.
        assertThat(context.items().get(0).claimCapability().state()).isEqualTo(ClaimCapabilityState.AVAILABLE);
    }

    // ---- Scenario 4: final-sale item ----

    @Test
    void finalSaleItemIsReturnDeniedWithStableCode() {
        Fixture f = new Fixture(BigDecimal.valueOf(500), item("Clearance Jacket", 1, true, true))
                .paid(PaymentMethod.CARD, PaymentStatus.PAID)
                .deliveredShipment(Instant.now());

        OrderSupportContext context = f.context();

        var returnCapability = context.items().get(0).returnCapability();
        assertThat(returnCapability.available()).isFalse();
        assertThat(returnCapability.denialReason()).isEqualTo("ITEM_FINAL_SALE");
    }

    // ---- Scenarios 5-7: partial previous return + richer return history ----

    @Test
    void partialReturnReducesMaxReturnableQuantityAndHistoryShowsExactQuantity() {
        OrderItem shoes = item("Running Shoes", 2, true, false);
        Fixture f = new Fixture(BigDecimal.valueOf(1000), shoes)
                .paid(PaymentMethod.CARD, PaymentStatus.PAID)
                .deliveredShipment(Instant.now())
                .returnOf(shoes, 1);
        // Simulate a received-and-inspected lifecycle stage.
        f.returnItems.get(0).setCondition(ItemCondition.USED);
        f.returns.get(0).setApprovedAt(Instant.now().minus(2, ChronoUnit.DAYS));
        f.returns.get(0).setReceivedAt(Instant.now().minus(1, ChronoUnit.DAYS));
        f.returns.get(0).setInspectedAt(Instant.now().minus(12, ChronoUnit.HOURS));

        OrderSupportContext context = f.context();

        var returnCapability = context.items().get(0).returnCapability();
        assertThat(returnCapability.available()).isTrue();
        assertThat(returnCapability.maxReturnableQuantity()).isEqualTo(1);

        assertThat(context.returns()).hasSize(1);
        var returnView = context.returns().get(0);
        assertThat(returnView.status().name()).isEqualTo("REQUESTED");
        assertThat(returnView.reason()).isEqualTo("WRONG_SIZE");
        assertThat(returnView.requestedAt()).isNotNull();
        assertThat(returnView.approvedAt()).isNotNull();
        assertThat(returnView.receivedAt()).isNotNull();
        assertThat(returnView.inspectedAt()).isNotNull();
        assertThat(returnView.completedAt()).isNull();
        assertThat(returnView.items()).hasSize(1);
        var itemView = returnView.items().get(0);
        assertThat(itemView.productName()).isEqualTo("Running Shoes");
        assertThat(itemView.quantity()).isEqualTo(1);
        assertThat(itemView.reason()).isEqualTo("WRONG_SIZE");
        assertThat(itemView.condition()).isEqualTo("USED");
    }

    @Test
    void uninspectedReturnLeavesConditionEmpty() {
        OrderItem shoes = item("Running Shoes", 1, true, false);
        Fixture f = new Fixture(BigDecimal.valueOf(500), shoes)
                .paid(PaymentMethod.CARD, PaymentStatus.PAID)
                .deliveredShipment(Instant.now())
                .returnOf(shoes, 1);

        OrderSupportContext context = f.context();

        var itemView = context.returns().get(0).items().get(0);
        assertThat(itemView.condition()).isNull();
        assertThat(context.returns().get(0).inspectedAt()).isNull();
    }

    // ---- Scenario 6: cancelled order ----

    @Test
    void cancelledOrderDeniesCancellationReturnAndClaimCapabilities() {
        Fixture f = new Fixture(BigDecimal.valueOf(500))
                .paid(PaymentMethod.CARD, PaymentStatus.PAID)
                .cancelled();

        OrderSupportContext context = f.context();

        assertThat(context.cancellation().available()).isFalse();
        assertThat(context.cancellation().denialReason()).isEqualTo("ALREADY_CANCELLED");
        var claimCapability = context.items().get(0).claimCapability();
        assertThat(claimCapability.state()).isEqualTo(ClaimCapabilityState.UNAVAILABLE);
        assertThat(claimCapability.denialReason()).isEqualTo("ORDER_CANCELLED");
        assertThat(claimCapability.resolution()).isNull();
        assertThat(claimCapability.existingClaim()).isNull();
        assertThat(context.supportCapabilities().anyClaimAvailable()).isFalse();
    }

    // ---- Scenarios 7-10: failed refund + richer refund history ----

    @Test
    void failedRefundIsDescriptiveWithReasonAndRelatedReturnNumber() {
        OrderItem shoes = item("Running Shoes", 1, true, false);
        Fixture f = new Fixture(BigDecimal.valueOf(500), shoes)
                .paid(PaymentMethod.CARD, PaymentStatus.PAID)
                .deliveredShipment(Instant.now())
                .returnOf(shoes, 1)
                .failedRefund();
        f.refunds.get(0).setProviderReference("PROV-REF-MUST-NOT-LEAK");

        OrderSupportContext context = f.context();

        var refundState = context.supportCapabilities().refundState();
        assertThat(refundState.hasRefunds()).isTrue();
        assertThat(refundState.latestStatus()).isEqualTo("FAILED");
        assertThat(refundState.hasFailedRefund()).isTrue();
        assertThat(refundState.hasPendingRefund()).isFalse();

        assertThat(context.refunds()).hasSize(1);
        var refund = context.refunds().get(0);
        assertThat(refund.status().name()).isEqualTo("FAILED");
        assertThat(refund.reason()).isEqualTo("RETURN");
        assertThat(refund.failedAt()).isNotNull();
        assertThat(refund.relatedReturnNumber()).isEqualTo(f.returns.get(0).getReturnNumber());
    }

    @Test
    void cancellationRefundHasNoRelatedReturnNumber() {
        Fixture f = new Fixture(BigDecimal.valueOf(500))
                .paid(PaymentMethod.CARD, PaymentStatus.PAID)
                .cancelled()
                .cancellationRefund();

        OrderSupportContext context = f.context();

        assertThat(context.refunds()).hasSize(1);
        var refund = context.refunds().get(0);
        assertThat(refund.reason()).isEqualTo("CANCELLATION");
        assertThat(refund.status().name()).isEqualTo("SUCCEEDED");
        assertThat(refund.completedAt()).isNotNull();
        assertThat(refund.relatedReturnNumber()).isNull();
    }

    // ---- Scenarios 1-4 of Task 9: claim capability states ----

    @Test
    void openClaimMakesItemExistingActiveAndClearsAvailability() {
        OrderItem shoes = item("Running Shoes", 1, true, false);
        Fixture f = new Fixture(BigDecimal.valueOf(500), shoes)
                .paid(PaymentMethod.CARD, PaymentStatus.PAID)
                .deliveredShipment(Instant.now())
                .claim(shoes, ClaimStatus.OPEN);

        OrderSupportContext context = f.context();

        var claimCapability = context.items().get(0).claimCapability();
        assertThat(claimCapability.state()).isEqualTo(ClaimCapabilityState.EXISTING_ACTIVE);
        assertThat(claimCapability.denialReason()).isNull();
        assertThat(claimCapability.resolution()).isNull();
        assertThat(claimCapability.existingClaim()).isNotNull();
        assertThat(claimCapability.existingClaim().claimNumber()).isEqualTo(f.claims.get(0).getClaimNumber());
        assertThat(claimCapability.existingClaim().status()).isEqualTo("OPEN");
        assertThat(claimCapability.existingClaim().reason()).isEqualTo("DAMAGED");
        assertThat(context.supportCapabilities().anyClaimAvailable()).isFalse();
    }

    @Test
    void inReviewClaimMakesItemExistingActive() {
        OrderItem shoes = item("Running Shoes", 1, true, false);
        Fixture f = new Fixture(BigDecimal.valueOf(500), shoes)
                .paid(PaymentMethod.CARD, PaymentStatus.PAID)
                .deliveredShipment(Instant.now())
                .claim(shoes, ClaimStatus.IN_REVIEW);

        OrderSupportContext context = f.context();

        assertThat(context.items().get(0).claimCapability().state()).isEqualTo(ClaimCapabilityState.EXISTING_ACTIVE);
        assertThat(context.items().get(0).claimCapability().existingClaim()).isNotNull();
        assertThat(context.supportCapabilities().anyClaimAvailable()).isFalse();
    }

    @Test
    void resolvedAndRejectedClaimsLeaveItemAvailable() {
        OrderItem shoes = item("Running Shoes", 1, true, false);
        OrderItem jacket = item("Denim Jacket", 1, true, false);
        Fixture f = new Fixture(BigDecimal.valueOf(1000), shoes, jacket)
                .paid(PaymentMethod.CARD, PaymentStatus.PAID)
                .deliveredShipment(Instant.now())
                .claim(shoes, ClaimStatus.RESOLVED)
                .claim(jacket, ClaimStatus.REJECTED);

        OrderSupportContext context = f.context();

        for (var itemView : context.items()) {
            assertThat(itemView.claimCapability().state()).isEqualTo(ClaimCapabilityState.AVAILABLE);
            assertThat(itemView.claimCapability().resolution()).isEqualTo("MANUAL_REVIEW");
            assertThat(itemView.claimCapability().existingClaim()).isNull();
        }
        assertThat(context.supportCapabilities().anyClaimAvailable()).isTrue();
        // History still shows both claims.
        assertThat(context.claims()).hasSize(2);
    }

    @Test
    void multiItemOrderWithOneActiveClaimKeepsOtherItemAvailable() {
        OrderItem shoes = item("Running Shoes", 1, true, false);
        OrderItem jacket = item("Denim Jacket", 1, true, false);
        Fixture f = new Fixture(BigDecimal.valueOf(1000), shoes, jacket)
                .paid(PaymentMethod.CARD, PaymentStatus.PAID)
                .deliveredShipment(Instant.now())
                .claim(jacket, ClaimStatus.OPEN);

        OrderSupportContext context = f.context();

        var shoesView = context.items().get(0);
        var jacketView = context.items().get(1);
        assertThat(shoesView.claimCapability().state()).isEqualTo(ClaimCapabilityState.AVAILABLE);
        assertThat(shoesView.claimCapability().existingClaim()).isNull();
        assertThat(jacketView.claimCapability().state()).isEqualTo(ClaimCapabilityState.EXISTING_ACTIVE);
        assertThat(jacketView.claimCapability().existingClaim()).isNotNull();
        assertThat(context.supportCapabilities().anyClaimAvailable()).isTrue();
        // Return capabilities stay independent.
        assertThat(shoesView.returnCapability().available()).isTrue();
        assertThat(jacketView.returnCapability().available()).isTrue();
    }

    // ---- Scenarios 11-12: claim history ----

    @Test
    void claimHistoryExposesCreatedAtAndSupportTicket() {
        OrderItem shoes = item("Running Shoes", 1, true, false);
        Fixture f = new Fixture(BigDecimal.valueOf(500), shoes)
                .paid(PaymentMethod.CARD, PaymentStatus.PAID)
                .deliveredShipment(Instant.now())
                .claim(shoes, ClaimStatus.IN_REVIEW);
        SupportTicket ticket = new SupportTicket("TKT-9001", f.customer, f.order,
                TicketCategory.CLAIM, TicketPriority.MEDIUM, "Damaged item claim");
        ticket.setStatus(TicketStatus.IN_PROGRESS);
        f.claims.get(0).setSupportTicket(ticket);

        OrderSupportContext context = f.context();

        assertThat(context.claims()).hasSize(1);
        var claimView = context.claims().get(0);
        assertThat(claimView.createdAt()).isNotNull();
        assertThat(claimView.itemName()).isEqualTo("Running Shoes");
        assertThat(claimView.supportTicketNumber()).isEqualTo("TKT-9001");
        assertThat(claimView.supportTicketStatus()).isEqualTo("IN_PROGRESS");
    }

    // ---- Scenario 9: independent multi-item capabilities + bulk query shape ----

    @Test
    void multiItemOrderComputesIndependentCapabilities() {
        OrderItem shoes = item("Running Shoes", 2, true, false);
        OrderItem jacket = item("Clearance Jacket", 1, true, true);
        Fixture f = new Fixture(BigDecimal.valueOf(1500), shoes, jacket)
                .paid(PaymentMethod.CARD, PaymentStatus.PAID)
                .deliveredShipment(Instant.now())
                .returnOf(shoes, 1);

        OrderSupportContext context = f.context();

        var shoesView = context.items().get(0);
        var jacketView = context.items().get(1);
        assertThat(shoesView.returnCapability().available()).isTrue();
        assertThat(shoesView.returnCapability().maxReturnableQuantity()).isEqualTo(1);
        assertThat(jacketView.returnCapability().available()).isFalse();
        assertThat(jacketView.returnCapability().denialReason()).isEqualTo("ITEM_FINAL_SALE");
        assertThat(context.supportCapabilities().anyReturnAvailable()).isTrue();
        assertThat(context.supportCapabilities().anyClaimAvailable()).isTrue();
    }

    @Test
    void aggregateReadIssuesOneShipmentQueryAndNoPerItemReturnItemQueries() {
        Fixture f = new Fixture(BigDecimal.valueOf(1500),
                item("A", 1, true, false), item("B", 1, true, false), item("C", 1, true, false))
                .paid(PaymentMethod.CARD, PaymentStatus.PAID)
                .deliveredShipment(Instant.now());

        f.context();

        // The aggregate read loads shipments once for the history section and
        // reuses that same list inside evaluateAll (Phase 7) - no duplicate
        // shipment query. This pins the read-wide bound: one bulk return-item
        // query per order and no per-item queries.
        verify(shipmentRepository, times(1)).findByOrderId(any());
        verify(returnItemRepository, never()).findByOrderItemId(any());
        verify(returnItemRepository, times(1)).findByOrderItemIdIn(any());
    }

    // ---- payment states: COD, authorized, paid, missing ----

    @Test
    void codAuthorizedPaidAndMissingPaymentsDoNotBreakTheContext() {
        Fixture cod = new Fixture(BigDecimal.valueOf(500))
                .paid(PaymentMethod.COD, PaymentStatus.PENDING);
        assertThat(cod.context().payment().status().name()).isEqualTo("PENDING");

        Fixture authorized = new Fixture(BigDecimal.valueOf(500))
                .paid(PaymentMethod.CARD, PaymentStatus.AUTHORIZED);
        assertThat(authorized.context().payment().status().name()).isEqualTo("AUTHORIZED");
        assertThat(authorized.context().cancellation().paymentConsequence()).isEqualTo("VOID_AUTHORIZATION");

        Fixture paid = new Fixture(BigDecimal.valueOf(500))
                .paid(PaymentMethod.CARD, PaymentStatus.PAID);
        assertThat(paid.context().payment().status().name()).isEqualTo("PAID");

        Fixture missing = new Fixture(BigDecimal.valueOf(500));
        OrderSupportContext context = missing.context();
        assertThat(context.payment()).isNull();
        assertThat(context.cancellation().available()).isFalse();
        assertThat(context.cancellation().denialReason()).isEqualTo("NO_PAYMENT_RECORD");
    }

    // ---- no leak: no SKU/internal ID in serialized JSON ----

    @Test
    void serializedContextLeaksNoInternalIdentifiers() throws Exception {
        OrderItem shoes = item("Running Shoes", 1, true, false);
        Fixture f = new Fixture(BigDecimal.valueOf(500), shoes)
                .paid(PaymentMethod.CARD, PaymentStatus.PAID)
                .deliveredShipment(Instant.now())
                .returnOf(shoes, 1)
                .failedRefund()
                .claim(shoes, ClaimStatus.OPEN);
        String secretProviderRef = "PROV-REF-" + UUID.randomUUID();
        f.refunds.get(0).setProviderReference(secretProviderRef);
        String orderUuid = f.order.getId().toString();
        String itemUuid = shoes.getId().toString();
        String paymentUuid = f.payment.getId().toString();

        String json = JsonMapper.builder().findAndAddModules().build().writeValueAsString(f.context());

        assertThat(json).doesNotContain("SKU-");
        assertThat(json).doesNotContain("sku");
        assertThat(json).doesNotContain(f.customerId.toString());
        assertThat(json).doesNotContain(orderUuid);
        assertThat(json).doesNotContain(itemUuid);
        assertThat(json).doesNotContain(paymentUuid);
        assertThat(json).doesNotContain(secretProviderRef);
        assertThat(json).doesNotContain("providerReference");
        assertThat(json).doesNotContain("procedureId");
        assertThat(json).doesNotContain("challengeId");
        assertThat(json).doesNotContain("\"otp\"");
        // Capability data is present.
        assertThat(json).contains("EXISTING_ACTIVE");
        assertThat(json).contains("maxReturnableQuantity");
    }

    // ---- empty history defaults ----

    @Test
    void emptyHistoriesProduceSaneDefaults() {
        Fixture f = new Fixture(BigDecimal.valueOf(500))
                .paid(PaymentMethod.CARD, PaymentStatus.PAID);

        OrderSupportContext context = f.context();

        assertThat(context.returns()).isEmpty();
        assertThat(context.refunds()).isEmpty();
        assertThat(context.claims()).isEmpty();
        assertThat(context.shipments()).isEmpty();
        var refundState = context.supportCapabilities().refundState();
        assertThat(refundState.hasRefunds()).isFalse();
        assertThat(refundState.latestStatus()).isNull();
        assertThat(refundState.hasFailedRefund()).isFalse();
        assertThat(refundState.hasPendingRefund()).isFalse();
        assertThat(context.supportCapabilities().anyReturnAvailable()).isFalse();
        assertThat(context.supportCapabilities().anyClaimAvailable()).isTrue();
    }
}
