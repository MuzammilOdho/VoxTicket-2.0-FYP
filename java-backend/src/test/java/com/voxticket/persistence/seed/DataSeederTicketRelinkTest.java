package com.voxticket.persistence.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.voxticket.persistence.entity.OrderClaim;
import com.voxticket.persistence.repository.CustomerRepository;
import com.voxticket.persistence.repository.OrderClaimRepository;
import com.voxticket.persistence.repository.OrderRepository;
import com.voxticket.persistence.repository.PaymentRepository;
import com.voxticket.persistence.repository.RefundRepository;
import com.voxticket.persistence.repository.ReturnRequestRepository;
import com.voxticket.persistence.repository.ShipmentRepository;
import com.voxticket.persistence.repository.SupportTicketRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Regression test for the ApplicationContext load failure seen in
 * {@code ProcedureCoordinatorCommitFailureTest} after the Phase 3 seeder
 * rewrite.
 *
 * <p>That test replaces {@code SupportTicketRepository} with a Mockito mock
 * (to simulate a ticket commit failure), so {@code save()} returns null and
 * no ticket is ever persisted. The rewritten seeder linked claims directly
 * to the in-memory ticket instances; at flush Hibernate then threw "object
 * references an unsaved transient instance", the {@code @Transactional}
 * seeder runner failed, and the whole ApplicationContext failed to load.
 * The pre-Phase-3 seeder never had this problem because it linked each
 * claim to the ticket save's <em>return value</em> (null under the mock).
 *
 * <p>This test drives {@link DataSeeder#run} with the same mock shape and
 * asserts the invariant at the seeder boundary, before Hibernate is ever
 * involved: no claim handed to the claim repository may reference a
 * transient ticket. No Spring context or Docker needed.
 */
class DataSeederTicketRelinkTest {

    @Test
    void mockedTicketRepository_claimsAreSavedWithNullTicketNeverATransientOne() {
        SupportTicketRepository tickets = mock(SupportTicketRepository.class);
        // Mirrors @MockitoBean default: save() returns null, nothing persisted.
        when(tickets.save(any())).thenReturn(null);
        OrderClaimRepository claims = mock(OrderClaimRepository.class);

        new DataSeeder(
                        mock(CustomerRepository.class),
                        mock(OrderRepository.class),
                        mock(PaymentRepository.class),
                        mock(ShipmentRepository.class),
                        mock(ReturnRequestRepository.class),
                        mock(RefundRepository.class),
                        claims,
                        tickets)
                .run();

        ArgumentCaptor<OrderClaim> savedClaims = ArgumentCaptor.forClass(OrderClaim.class);
        verify(claims, atLeastOnce()).save(savedClaims.capture());
        assertThat(savedClaims.getAllValues())
                .as("every seeded claim must reach the repository")
                .hasSize(8);
        for (OrderClaim claim : savedClaims.getAllValues()) {
            assertThat(claim.getSupportTicket())
                    .as("claim %s must not reference a transient ticket", claim.getClaimNumber())
                    .isNull();
        }
        // The ticket-linked claims (ORD-10012, ORD-10038, ORD-10041) are present.
        assertThat(savedClaims.getAllValues())
                .map(c -> c.getOrder().getOrderNumber())
                .contains("ORD-10012", "ORD-10038", "ORD-10041");
    }

    @Test
    void realTicketRepository_claimTicketLinkIsPreserved() {
        SupportTicketRepository tickets = mock(SupportTicketRepository.class);
        // Mirrors the real repository: save() returns the managed instance.
        when(tickets.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        OrderClaimRepository claims = mock(OrderClaimRepository.class);

        new DataSeeder(
                        mock(CustomerRepository.class),
                        mock(OrderRepository.class),
                        mock(PaymentRepository.class),
                        mock(ShipmentRepository.class),
                        mock(ReturnRequestRepository.class),
                        mock(RefundRepository.class),
                        claims,
                        tickets)
                .run();

        ArgumentCaptor<OrderClaim> savedClaims = ArgumentCaptor.forClass(OrderClaim.class);
        verify(claims, atLeastOnce()).save(savedClaims.capture());
        long linked = savedClaims.getAllValues().stream()
                .filter(c -> c.getSupportTicket() != null)
                .count();
        assertThat(linked).as("three seeded claims link tickets").isEqualTo(3);
        assertThat(savedClaims.getAllValues().stream()
                        .filter(c -> c.getSupportTicket() != null)
                        .map(c -> c.getOrder().getOrderNumber())
                        .toList())
                .containsExactlyInAnyOrder("ORD-10012", "ORD-10038", "ORD-10041");
        for (OrderClaim claim : savedClaims.getAllValues()) {
            if (claim.getSupportTicket() != null) {
                assertThat(claim.getSupportTicket().getTicketNumber()).startsWith("TCK-");
            }
        }
    }
}
