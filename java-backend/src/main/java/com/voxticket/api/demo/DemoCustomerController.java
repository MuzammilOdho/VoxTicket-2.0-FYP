package com.voxticket.api.demo;

import com.voxticket.persistence.entity.Customer;
import com.voxticket.persistence.entity.Order;
import com.voxticket.persistence.repository.CustomerRepository;
import com.voxticket.persistence.repository.OrderRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Demo support: serves a seeded customer and their orders to the public demo
 * page, so the UI tests as a real customer instead of hardcoded personas.
 *
 * <p>Dev/test only, like the chat and voice surfaces: read-only, and the
 * chat endpoint already lets a dev caller assert an arbitrary customerPhone,
 * so this exposes nothing new - it just makes the demo honest about which
 * customer it is testing as. Never enable outside dev/test.
 */
@RestController
@RequestMapping("/api/v1/demo")
@Profile({"dev", "test"})
public class DemoCustomerController {

    private final CustomerRepository customers;
    private final OrderRepository orders;

    public DemoCustomerController(CustomerRepository customers, OrderRepository orders) {
        this.customers = customers;
        this.orders = orders;
    }

    /** A random seeded customer that has at least one order. Each page load assigns a new one. */
    @GetMapping("/customer/random")
    @Transactional(readOnly = true)
    public DemoCustomerDto randomCustomer() {
        List<Customer> withOrders = customers.findAll().stream()
                .filter(c -> !orders
                        .findByCustomerIdOrderByPlacedAtDesc(c.getId(), PageRequest.of(0, 1))
                        .isEmpty())
                .toList();
        if (withOrders.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No seeded customers with orders found");
        }
        List<Customer> shuffled = new ArrayList<>(withOrders);
        Collections.shuffle(shuffled);
        return toDto(shuffled.get(0));
    }

    /** Look up a seeded customer by E.164 phone number. */
    @GetMapping("/customer")
    @Transactional(readOnly = true)
    public DemoCustomerDto customerByPhone(@RequestParam("phone") String phone) {
        Customer customer = customers
                .findByPhone(phone)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No customer with phone " + phone));
        return toDto(customer);
    }

    private DemoCustomerDto toDto(Customer customer) {
        List<DemoOrderDto> recent = orders
                .findByCustomerIdOrderByPlacedAtDesc(customer.getId(), PageRequest.of(0, 5))
                .stream()
                .map(this::toOrderDto)
                .toList();
        return new DemoCustomerDto(
                customer.getPhone(),
                customer.getFullName(),
                customer.getEmail(),
                customer.getStatus().name(),
                recent);
    }

    private DemoOrderDto toOrderDto(Order order) {
        String items = order.getItems().stream()
                .map(i -> i.getQuantity() + " × " + i.getProductName())
                .reduce((a, b) -> a + ", " + b)
                .orElse("—");
        return new DemoOrderDto(
                order.getOrderNumber(),
                order.getOrderStatus().name(),
                order.getFulfillmentStatus().name(),
                items,
                order.getTotalAmount().toPlainString(),
                order.getCurrency());
    }
}
