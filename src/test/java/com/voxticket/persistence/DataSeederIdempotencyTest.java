package com.voxticket.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.voxticket.persistence.repository.CustomerRepository;
import com.voxticket.persistence.repository.OrderRepository;
import com.voxticket.persistence.seed.DataSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class DataSeederIdempotencyTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private DataSeeder dataSeeder;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private OrderRepository orderRepository;

    @Test
    void seederPopulatesExpectedScenarioDataOnce() {
        // The seeder already ran once during context startup (CommandLineRunner).
        long customersAfterStartup = customerRepository.count();
        long ordersAfterStartup = orderRepository.count();
        assertThat(customersAfterStartup).isEqualTo(3);
        assertThat(ordersAfterStartup).isEqualTo(14);

        // Running it again must be a no-op.
        dataSeeder.run();

        assertThat(customerRepository.count()).isEqualTo(customersAfterStartup);
        assertThat(orderRepository.count()).isEqualTo(ordersAfterStartup);
    }
}