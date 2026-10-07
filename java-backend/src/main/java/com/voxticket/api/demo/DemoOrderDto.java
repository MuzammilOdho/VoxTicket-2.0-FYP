package com.voxticket.api.demo;

/** One order line for the demo customer card. Amounts are plain strings to keep the demo dumb. */
public record DemoOrderDto(
        String number, String status, String fulfillmentStatus, String items, String total, String currency) {}
