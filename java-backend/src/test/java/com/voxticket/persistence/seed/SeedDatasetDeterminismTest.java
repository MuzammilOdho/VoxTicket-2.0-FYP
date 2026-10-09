package com.voxticket.persistence.seed;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.voxticket.persistence.entity.enums.ClaimStatus;
import com.voxticket.persistence.entity.enums.FulfillmentStatus;
import com.voxticket.persistence.entity.enums.OrderStatus;
import com.voxticket.persistence.entity.enums.PaymentStatus;
import com.voxticket.persistence.entity.enums.RefundStatus;
import com.voxticket.persistence.entity.enums.ReturnStatus;
import com.voxticket.persistence.entity.enums.ShipmentStatus;
import com.voxticket.persistence.entity.enums.TicketStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Phase 3: the seeded dataset must be fully deterministic and internally
 * consistent.
 *
 * <p>The headline check builds the dataset twice and diffs a canonical
 * snapshot: generation is a pure function of the fixed anchor and the fixed
 * random seed, so the two snapshots must be character-identical. Surrogate
 * UUIDs are excluded from the snapshot on purpose - Hibernate assigns them
 * randomly at persist time; business references are the stable identity.
 */
class SeedDatasetDeterminismTest {

    @Test
    void buildTwice_producesIdenticalCanonicalSnapshot() {
        String first = SeedDatasetSnapshot.render(SeedDatasetFactory.build());
        String second = SeedDatasetSnapshot.render(SeedDatasetFactory.build());

        assertThat(second).isEqualTo(first);
        assertThat(first).isNotBlank();
    }

    @Test
    void noTimestampComesFromTheWallClock() {
        // Any Instant.now() leak inside generation would land within a day of the
        // real wall clock. Every legitimate dataset timestamp is anchor-relative
        // (the latest is an estimated delivery a few days after the 2026-09-01
        // anchor), so a one-day cutoff separates leaks from real data cleanly.
        Instant wallClockCutoff = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant lowerBound = SeedDatasetFactory.ANCHOR.minus(400, ChronoUnit.DAYS);
        for (Instant ts : SeedDatasetSnapshot.allTimestamps(SeedDatasetFactory.build())) {
            assertThat(ts).as("timestamp %s looks like a wall-clock leak", ts).isBefore(wallClockCutoff);
            assertThat(ts).as("timestamp %s is implausibly old", ts).isAfter(lowerBound);
        }
    }

    @Test
    void businessNumbersAreUnique() {
        SeedDataset ds = SeedDatasetFactory.build();
        assertUnique("order", ds.getOrders().stream().map(Order::getOrderNumber).toList());
        assertUnique("refund", ds.getRefunds().stream().map(Refund::getRefundNumber).toList());
        assertUnique("return", ds.getReturnRequests().stream().map(ReturnRequest::getReturnNumber).toList());
        assertUnique("claim", ds.getOrderClaims().stream().map(OrderClaim::getClaimNumber).toList());
        assertUnique("ticket", ds.getSupportTickets().stream().map(SupportTicket::getTicketNumber).toList());
        assertUnique("customer email", ds.getCustomers().stream().map(Customer::getEmail).toList());
        assertUnique("customer phone", ds.getCustomers().stream().map(Customer::getPhone).toList());
        assertUnique("tracking", ds.getShipments().stream().map(Shipment::getTrackingNumber).toList());
    }

    @Test
    void orderMoneyIsConsistent() {
        for (Order order : SeedDatasetFactory.build().getOrders()) {
            BigDecimal itemsTotal = order.getItems().stream()
                    .map(i -> i.getUnitPrice().multiply(BigDecimal.valueOf(i.getQuantity())))
                    .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
            assertThat(order.getSubtotal())
                    .as("subtotal of %s", order.getOrderNumber())
                    .isEqualByComparingTo(itemsTotal);
            assertThat(order.getTotalAmount())
                    .as("total of %s", order.getOrderNumber())
                    .isEqualByComparingTo(order.getSubtotal().add(order.getShippingAmount()));
        }
    }

    @Test
    void atMostOneActiveClaimPerOrderItem() {
        // Mirrors the V7 partial unique index: OPEN/IN_REVIEW claims are the "active" ones.
        SeedDataset ds = SeedDatasetFactory.build();
        Map<String, Integer> activePerItem = new HashMap<>();
        for (OrderClaim claim : ds.getOrderClaims()) {
            if (claim.getStatus() == ClaimStatus.OPEN || claim.getStatus() == ClaimStatus.IN_REVIEW) {
                String key = claim.getOrder().getOrderNumber() + "#" + claim.getOrderItem().getSku();
                activePerItem.merge(key, 1, Integer::sum);
            }
        }
        assertThat(activePerItem.values()).allMatch(count -> count <= 1);
        // And the dataset actually exercises the refile edge: ORD-10040 carries a
        // REJECTED claim plus a fresh OPEN one on the same item.
        Order refilled = ds.getOrders().stream()
                .filter(o -> o.getOrderNumber().equals("ORD-10040")).findFirst().orElseThrow();
        String refilledKey = "ORD-10040#" + refilled.getItems().get(0).getSku();
        assertThat(activePerItem).containsKey(refilledKey);
        long totalForItem = ds.getOrderClaims().stream()
                .filter(c -> c.getOrder().getOrderNumber().equals("ORD-10040")
                        && c.getOrderItem().getSku().equals(refilled.getItems().get(0).getSku()))
                .count();
        assertThat(totalForItem).isEqualTo(2);
    }

    @Test
    void datasetHasExpectedScale() {
        SeedDataset ds = SeedDatasetFactory.build();
        assertThat(ds.getCustomers()).hasSize(SeedDatasetFactory.EXPECTED_CUSTOMERS);
        assertThat(ds.getOrders()).hasSize(SeedDatasetFactory.EXPECTED_ORDERS);
        assertThat(SeedDatasetFactory.EXPECTED_CUSTOMERS).isEqualTo(12);
        assertThat(SeedDatasetFactory.EXPECTED_ORDERS).isEqualTo(54);
    }

    @Test
    void lifecycleEnumsAreFullyCovered() {
        SeedDataset ds = SeedDatasetFactory.build();
        assertThat(ds.getShipments().stream().map(Shipment::getStatus).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder(ShipmentStatus.values());
        assertThat(ds.getReturnRequests().stream().map(ReturnRequest::getStatus).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder(ReturnStatus.values());
        assertThat(ds.getOrderClaims().stream().map(OrderClaim::getStatus).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder(ClaimStatus.values());
        assertThat(ds.getRefunds().stream().map(Refund::getStatus).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder(RefundStatus.values());
        assertThat(ds.getPayments().stream().map(Payment::getStatus).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder(PaymentStatus.values());
        assertThat(ds.getSupportTickets().stream().map(SupportTicket::getStatus).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder(TicketStatus.values());
        assertThat(ds.getOrders().stream().map(Order::getOrderStatus).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder(OrderStatus.values());
        assertThat(ds.getOrders().stream().map(Order::getFulfillmentStatus).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder(FulfillmentStatus.values());
    }

    private static void assertUnique(String what, List<String> values) {
        assertThat(new HashSet<>(values)).as("duplicate %s numbers", what).hasSize(values.size());
    }

    // ------------------------------------------------------------------
    // Canonical snapshot: every business field, FKs as business keys,
    // sorted, surrogate UUIDs excluded.
    // ------------------------------------------------------------------

    static final class SeedDatasetSnapshot {

        static String render(SeedDataset ds) {
            List<String> lines = new ArrayList<>();
            ds.getCustomers().stream()
                    .sorted(Comparator.comparing(Customer::getEmail))
                    .forEach(c -> lines.add("customer|" + join(c.getEmail(), c.getFirstName(), c.getLastName(),
                            c.getPhone(), ts(c.getCreatedAt()))));
            ds.getOrders().stream()
                    .sorted(Comparator.comparing(Order::getOrderNumber))
                    .forEach(o -> {
                        lines.add("order|" + join(o.getOrderNumber(), o.getCustomer().getEmail(),
                                o.getOrderStatus(), o.getFulfillmentStatus(), o.getCurrency(),
                                bd(o.getSubtotal()), bd(o.getShippingAmount()), bd(o.getTotalAmount()),
                                ts(o.getPlacedAt()), ts(o.getCancelledAt()), ts(o.getCompletedAt())));
                        o.getItems().stream()
                                .sorted(Comparator.comparing(OrderItem::getSku))
                                .forEach(i -> lines.add("item|" + join(o.getOrderNumber() + "#" + i.getSku(),
                                        i.getProductName(), i.getQuantity(), bd(i.getUnitPrice()),
                                        i.isReturnable(), i.isFinalSale())));
                    });
            ds.getPayments().stream()
                    .sorted(Comparator.comparing(p -> p.getOrder().getOrderNumber()))
                    .forEach(p -> lines.add("payment|" + join(p.getOrder().getOrderNumber(), p.getMethod(),
                            bd(p.getAmount()), p.getCurrency(), p.getStatus(),
                            ts(p.getAuthorizedAt()), ts(p.getCapturedAt()), ts(p.getCreatedAt()))));
            ds.getShipments().stream()
                    .sorted(Comparator.comparing(Shipment::getTrackingNumber))
                    .forEach(s -> lines.add("shipment|" + join(s.getTrackingNumber(),
                            s.getOrder().getOrderNumber(), s.getCarrier(), s.getStatus(),
                            ts(s.getShippedAt()), ts(s.getEstimatedDeliveryAt()),
                            ts(s.getDeliveredAt()), ts(s.getUpdatedAt()))));
            ds.getReturnRequests().stream()
                    .sorted(Comparator.comparing(ReturnRequest::getReturnNumber))
                    .forEach(r -> {
                        lines.add("return|" + join(r.getReturnNumber(), r.getOrder().getOrderNumber(),
                                r.getReason(), r.getStatus(), ts(r.getRequestedAt()), ts(r.getApprovedAt()),
                                ts(r.getReceivedAt()), ts(r.getInspectedAt()), ts(r.getCompletedAt())));
                        r.getItems().stream()
                                .sorted(Comparator.comparing(i -> i.getOrderItem().getSku()))
                                .forEach(i -> lines.add("return-item|" + join(r.getReturnNumber() + "#"
                                        + r.getOrder().getOrderNumber() + "#" + i.getOrderItem().getSku(),
                                        i.getQuantity(), i.getReason())));
                    });
            ds.getRefunds().stream()
                    .sorted(Comparator.comparing(Refund::getRefundNumber))
                    .forEach(r -> lines.add("refund|" + join(r.getRefundNumber(), r.getOrder().getOrderNumber(),
                            r.getPayment().getOrder().getOrderNumber(),
                            r.getReturnRequest() == null ? null : r.getReturnRequest().getReturnNumber(),
                            bd(r.getAmount()), r.getReason(), r.getStatus(),
                            ts(r.getInitiatedAt()), ts(r.getCompletedAt()), ts(r.getFailedAt()))));
            ds.getSupportTickets().stream()
                    .sorted(Comparator.comparing(SupportTicket::getTicketNumber))
                    .forEach(t -> lines.add("ticket|" + join(t.getTicketNumber(), t.getCustomer().getEmail(),
                            t.getOrder().getOrderNumber(), t.getCategory(), t.getPriority(), t.getStatus(),
                            t.getSummary(), ts(t.getCreatedAt()))));
            ds.getOrderClaims().stream()
                    .sorted(Comparator.comparing(OrderClaim::getClaimNumber))
                    .forEach(c -> lines.add("claim|" + join(c.getClaimNumber(), c.getOrder().getOrderNumber(),
                            c.getOrder().getOrderNumber() + "#" + c.getOrderItem().getSku(),
                            c.getReason(), c.getRequestedResolution(), c.getStatus(),
                            c.getSupportTicket() == null ? null : c.getSupportTicket().getTicketNumber(),
                            ts(c.getCreatedAt()))));
            return String.join("\n", lines);
        }

        /** Every timestamp in the dataset, for the wall-clock-leak check. */
        static List<Instant> allTimestamps(SeedDataset ds) {
            List<Instant> out = new ArrayList<>();
            ds.getCustomers().forEach(c -> out.add(c.getCreatedAt()));
            ds.getOrders().forEach(o -> {
                out.add(o.getPlacedAt());
                addIfPresent(out, o.getCancelledAt());
                addIfPresent(out, o.getCompletedAt());
            });
            ds.getPayments().forEach(p -> {
                out.add(p.getCreatedAt());
                addIfPresent(out, p.getAuthorizedAt());
                addIfPresent(out, p.getCapturedAt());
            });
            ds.getShipments().forEach(s -> {
                addIfPresent(out, s.getShippedAt());
                addIfPresent(out, s.getEstimatedDeliveryAt());
                addIfPresent(out, s.getDeliveredAt());
                out.add(s.getUpdatedAt());
            });
            ds.getReturnRequests().forEach(r -> {
                out.add(r.getRequestedAt());
                addIfPresent(out, r.getApprovedAt());
                addIfPresent(out, r.getReceivedAt());
                addIfPresent(out, r.getInspectedAt());
                addIfPresent(out, r.getCompletedAt());
            });
            ds.getRefunds().forEach(r -> {
                out.add(r.getInitiatedAt());
                addIfPresent(out, r.getCompletedAt());
                addIfPresent(out, r.getFailedAt());
            });
            ds.getSupportTickets().forEach(t -> out.add(t.getCreatedAt()));
            ds.getOrderClaims().forEach(c -> out.add(c.getCreatedAt()));
            return out;
        }

        private static void addIfPresent(List<Instant> out, Instant ts) {
            if (ts != null) {
                out.add(ts);
            }
        }

        private static String join(Object... parts) {
            StringBuilder sb = new StringBuilder();
            for (Object part : parts) {
                if (sb.length() > 0) {
                    sb.append('|');
                }
                sb.append(part == null ? "-" : part);
            }
            return sb.toString();
        }

        private static String ts(Instant instant) {
            return instant == null ? "-" : instant.toString();
        }

        private static String bd(BigDecimal value) {
            return value == null ? "-" : value.toPlainString();
        }
    }
}
