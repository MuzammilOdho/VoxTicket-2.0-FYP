package com.voxticket.persistence.repository;

import com.voxticket.persistence.entity.Refund;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundRepository extends JpaRepository<Refund, UUID> {
    Optional<Refund> findByRefundNumber(String refundNumber);
    List<Refund> findByOrderId(UUID orderId);
    List<Refund> findByPaymentId(UUID paymentId);

    /**
     * Aggregate read-path fetch: refunds with their return request and
     * payment in one statement. The plain {@link #findByOrderId} leaves both
     * associations lazy: the support-context refund history paid one query
     * per refund for the return number, and {@code getLatestRefund} paid one
     * for the payment currency.
     */
    @Query("select r from Refund r "
            + "left join fetch r.returnRequest "
            + "left join fetch r.payment "
            + "where r.order.id = :orderId")
    List<Refund> findByOrderIdWithDetails(@Param("orderId") UUID orderId);

    /**
     * Row-locked refund fetch, available for read-modify-write flows that
     * need to serialize on a single refund row. Callers must already run
     * inside a transaction.
     *
     * <p>Note: the terminal transitions ({@code markSucceeded}/
     * {@code markFailed}) no longer rely on this. They use the atomic
     * conditional updates below instead, because a
     * "SELECT ... FOR UPDATE, check status in memory, mutate, flush at
     * commit" sequence still leaves a check-then-act window: a racing
     * thread can pass the in-memory PENDING check before the winner's
     * mutation reaches the database, so both transitions apply. The
     * conditional UPDATE folds the check and the mutation into one
     * statement, evaluated by the database while holding the row
     * write-lock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Refund r where r.id = :id")
    Optional<Refund> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Atomically moves a PENDING refund to SUCCEEDED. The
     * {@code status = PENDING} predicate is evaluated by the database while
     * holding the row write-lock taken by the UPDATE itself, so of any
     * racing terminal transitions exactly one can match: the winner updates
     * one row, every loser updates zero rows and must be rejected by the
     * caller.
     *
     * @return 1 when this call won the transition, 0 when the refund was
     *         already terminal (or the id is unknown)
     */
    @Modifying
    @Query("update Refund r set r.status = com.voxticket.persistence.entity.enums.RefundStatus.SUCCEEDED, "
            + "r.completedAt = :completedAt "
            + "where r.id = :id and r.status = com.voxticket.persistence.entity.enums.RefundStatus.PENDING")
    int markSucceededIfPending(@Param("id") UUID id, @Param("completedAt") Instant completedAt);

    /**
     * Atomically moves a PENDING refund to FAILED, with the same
     * exactly-one-winner guarantee as {@link #markSucceededIfPending}.
     *
     * @return 1 when this call won the transition, 0 when the refund was
     *         already terminal (or the id is unknown)
     */
    @Modifying
    @Query("update Refund r set r.status = com.voxticket.persistence.entity.enums.RefundStatus.FAILED, "
            + "r.failedAt = :failedAt "
            + "where r.id = :id and r.status = com.voxticket.persistence.entity.enums.RefundStatus.PENDING")
    int markFailedIfPending(@Param("id") UUID id, @Param("failedAt") Instant failedAt);
}
