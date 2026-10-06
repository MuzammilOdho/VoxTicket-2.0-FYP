package com.voxticket.persistence.repository;

import com.voxticket.persistence.entity.ReturnItem;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReturnItemRepository extends JpaRepository<ReturnItem, UUID> {
    List<ReturnItem> findByReturnRequestId(UUID returnRequestId);

    /**
     * Used by ReturnPolicyService to compute how much of an item's quantity is already tied up in returns.
     * The entity graph fetches the return request (for its status) and the order item (for its id) in the
     * same statement - without it every returned item paid two extra lazy queries.
     */
    @EntityGraph(attributePaths = {"returnRequest", "orderItem"})
    List<ReturnItem> findByOrderItemId(UUID orderItemId);

    /**
     * Bulk variant of {@link #findByOrderItemId} used by the aggregate
     * order-read path, so multi-item orders do not issue one return-item
     * query per item. Same entity graph: the per-item status/id hops are
     * fetched eagerly, not one lazy query per row.
     */
    @EntityGraph(attributePaths = {"returnRequest", "orderItem"})
    List<ReturnItem> findByOrderItemIdIn(Collection<UUID> orderItemIds);
}