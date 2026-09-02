package com.voxticket.persistence.repository;

import com.voxticket.persistence.entity.ReturnItem;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReturnItemRepository extends JpaRepository<ReturnItem, UUID> {
    List<ReturnItem> findByReturnRequestId(UUID returnRequestId);
}