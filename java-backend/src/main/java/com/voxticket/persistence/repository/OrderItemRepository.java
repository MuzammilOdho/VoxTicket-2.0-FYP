package com.voxticket.persistence.repository;

import com.voxticket.persistence.entity.OrderItem;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderItemRepository extends JpaRepository<OrderItem, UUID> {
    List<OrderItem> findByOrderId(UUID orderId);

    /**
     * Row-locked item fetch for active-claim filing. {@code ClaimService.fileClaim}
     * locks the item row before checking for existing active claims so two
     * concurrent filings for the same item serialize: the loser observes the
     * winner's committed claim and gets the clean domain error instead of
     * racing the insert. Callers must already run inside a transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from OrderItem i where i.id = :id")
    Optional<OrderItem> findByIdForUpdate(@Param("id") UUID id);
}
