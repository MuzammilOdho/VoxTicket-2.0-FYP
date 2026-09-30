package com.voxticket.persistence.seed;

import com.voxticket.persistence.entity.OrderClaim;
import com.voxticket.persistence.entity.SupportTicket;
import com.voxticket.persistence.repository.CustomerRepository;
import com.voxticket.persistence.repository.OrderClaimRepository;
import com.voxticket.persistence.repository.OrderRepository;
import com.voxticket.persistence.repository.PaymentRepository;
import com.voxticket.persistence.repository.RefundRepository;
import com.voxticket.persistence.repository.ReturnRequestRepository;
import com.voxticket.persistence.repository.ShipmentRepository;
import com.voxticket.persistence.repository.SupportTicketRepository;
import java.util.IdentityHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the realistic synthetic demo dataset (spec §65: no external commerce
 * integration for the FYP - this database is the system of record).
 *
 * <p>Generation lives in {@link SeedDatasetFactory} and is fully
 * deterministic: a fixed time anchor plus a fixed random seed, no wall-clock
 * reads. This runner only persists the generated graph, in foreign-key
 * dependency order.
 *
 * <p>Idempotent: if any customers already exist, seeding is skipped so
 * restarting the app never duplicates rows.
 */
@Component
@Profile({"dev", "test"})
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final CustomerRepository customerRepository;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final ShipmentRepository shipmentRepository;
    private final ReturnRequestRepository returnRequestRepository;
    private final RefundRepository refundRepository;
    private final OrderClaimRepository orderClaimRepository;
    private final SupportTicketRepository supportTicketRepository;

    public DataSeeder(
            CustomerRepository customerRepository,
            OrderRepository orderRepository,
            PaymentRepository paymentRepository,
            ShipmentRepository shipmentRepository,
            ReturnRequestRepository returnRequestRepository,
            RefundRepository refundRepository,
            OrderClaimRepository orderClaimRepository,
            SupportTicketRepository supportTicketRepository) {
        this.customerRepository = customerRepository;
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.shipmentRepository = shipmentRepository;
        this.returnRequestRepository = returnRequestRepository;
        this.refundRepository = refundRepository;
        this.orderClaimRepository = orderClaimRepository;
        this.supportTicketRepository = supportTicketRepository;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (customerRepository.count() > 0) {
            log.info("Seed data already present - skipping (idempotent seeding).");
            return;
        }
        log.info("Seeding VoxTicket demo data...");

        SeedDataset dataset = SeedDatasetFactory.build();
        dataset.getCustomers().forEach(customerRepository::save);
        // Order cascades to its items; ReturnRequest cascades to its return items.
        dataset.getOrders().forEach(orderRepository::save);
        dataset.getPayments().forEach(paymentRepository::save);
        dataset.getShipments().forEach(shipmentRepository::save);
        dataset.getReturnRequests().forEach(returnRequestRepository::save);
        dataset.getRefunds().forEach(refundRepository::save);
        // Claims reference tickets: re-point each claim at the persisted ticket
        // instance returned by the save. Where the repository is a test mock
        // (ProcedureCoordinatorCommitFailureTest replaces SupportTicketRepository
        // with a Mockito mock whose save() returns null), the claim keeps a
        // null ticket instead of a transient reference - exactly what the
        // pre-Phase-3 seeder did by linking the save's return value. Linking
        // the raw in-memory instance would make Hibernate fail the flush with
        // "object references an unsaved transient instance" and take the whole
        // ApplicationContext down with it.
        Map<SupportTicket, SupportTicket> persistedTickets = new IdentityHashMap<>();
        dataset.getSupportTickets()
                .forEach(ticket -> persistedTickets.put(ticket, supportTicketRepository.save(ticket)));
        for (OrderClaim claim : dataset.getOrderClaims()) {
            SupportTicket generated = claim.getSupportTicket();
            if (generated != null) {
                claim.setSupportTicket(persistedTickets.get(generated));
            }
        }
        dataset.getOrderClaims().forEach(orderClaimRepository::save);

        log.info("Seeding complete: {} customers, {} orders.", customerRepository.count(), orderRepository.count());
    }
}
