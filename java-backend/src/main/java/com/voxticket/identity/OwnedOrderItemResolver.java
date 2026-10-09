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

        String needle = stripLeadingFillerWords(trimmed).toLowerCase(Locale.ROOT);
        List<OrderItem> nameMatches = items.stream().filter(i -> matchesDescription(i.getProductName(), needle)).toList();
        if (nameMatches.size() == 1) {
            return nameMatches.get(0);
        }
        if (nameMatches.isEmpty()) {
            throw new ResourceNotFoundForAccountException("ORDER_ITEM", itemDescription);
        }
        throw new AmbiguousItemException(nameMatches);
    }

    /**
     * FIX: a plain substring check missed simple plural/singular mismatches - "bedsheets" is not
     * a substring of "Cotton Bedsheet Set" because of the space before "Set". Tries the singular
     * form (strip trailing 's') and the plural form (add trailing 's') as fallbacks.
     */
    private boolean matchesDescription(String productName, String needle) {
        String normalized = productName.toLowerCase(Locale.ROOT);
        if (normalized.contains(needle)) {
            return true;
        }
        if (needle.endsWith("s") && normalized.contains(needle.substring(0, needle.length() - 1))) {
            return true;
        }
        return normalized.contains(needle + "s");
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