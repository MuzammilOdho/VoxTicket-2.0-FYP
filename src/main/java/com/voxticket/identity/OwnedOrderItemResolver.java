package com.voxticket.identity;

import com.voxticket.persistence.entity.OrderItem;
import com.voxticket.persistence.repository.OrderItemRepository;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class OwnedOrderItemResolver {

    private final OrderItemRepository orderItemRepository;

    public OwnedOrderItemResolver(OrderItemRepository orderItemRepository) {
        this.orderItemRepository = orderItemRepository;
    }

    public OrderItem resolve(VerifiedOrderRef orderRef, String itemDescription) {
        List<OrderItem> items = orderItemRepository.findByOrderId(orderRef.orderId());
        if (items.isEmpty()) {
            throw new ResourceNotFoundForAccountException("ORDER_ITEM", String.valueOf(itemDescription));
        }
        if (items.size() == 1) {
            return items.get(0);
        }
        if (itemDescription == null || itemDescription.isBlank()) {
            throw new AmbiguousItemException(items);
        }

        String trimmed = itemDescription.trim();
        Optional<OrderItem> exactSku = items.stream().filter(i -> i.getSku().equalsIgnoreCase(trimmed)).findFirst();
        if (exactSku.isPresent()) {
            return exactSku.get();
        }

        // FIX: natural phrasing like "the t-shirt" or "my shoes" was failing a plain substring
        // check against "Cotton T-Shirt" - "the"/"my" aren't part of the product name. Stripping
        // a small set of common leading filler words before matching handles the single most
        // common real phrasing pattern without building a full NLP/stopword pipeline.
        String needle = stripLeadingFillerWords(trimmed).toLowerCase(Locale.ROOT);
        List<OrderItem> nameMatches = items.stream().filter(i -> i.getProductName().toLowerCase(Locale.ROOT).contains(needle)).toList();
        if (nameMatches.size() == 1) {
            return nameMatches.get(0);
        }
        if (nameMatches.isEmpty()) {
            throw new ResourceNotFoundForAccountException("ORDER_ITEM", itemDescription);
        }
        throw new AmbiguousItemException(nameMatches);
    }

    private String stripLeadingFillerWords(String text) {
        return text.replaceAll("(?i)^(the|a|an|my)\\s+", "").trim();
    }

    public OrderItem resolveBySku(VerifiedOrderRef orderRef, String sku) {
        return orderItemRepository.findByOrderId(orderRef.orderId()).stream()
                .filter(item -> item.getSku().equalsIgnoreCase(sku))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundForAccountException("ORDER_ITEM", sku));
    }
}