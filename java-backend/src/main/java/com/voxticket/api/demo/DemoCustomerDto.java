package com.voxticket.api.demo;

import java.util.List;

/** One seeded customer with a short order history, for the public demo page. */
public record DemoCustomerDto(
        String phone, String name, String email, String status, List<DemoOrderDto> orders) {}
