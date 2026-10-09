package com.voxticket.admin;

import com.voxticket.admin.dto.OperationDtos.ClaimDto;
import com.voxticket.admin.dto.OperationDtos.EscalationDto;
import com.voxticket.admin.dto.OperationDtos.OrderDto;
import com.voxticket.admin.dto.OperationDtos.RefundDto;
import com.voxticket.admin.dto.OperationDtos.ReturnDto;
import com.voxticket.admin.dto.OperationDtos.VerificationChallengeDto;
import com.voxticket.admin.dto.PageDto;
import com.voxticket.persistence.entity.Order;
import com.voxticket.persistence.entity.OrderClaim;
import com.voxticket.persistence.entity.Refund;
import com.voxticket.persistence.entity.ReturnRequest;
import com.voxticket.persistence.entity.SupportTicket;
import com.voxticket.persistence.entity.VerificationChallenge;
import com.voxticket.persistence.entity.enums.ClaimStatus;
import com.voxticket.persistence.entity.enums.OrderStatus;
import com.voxticket.persistence.entity.enums.RefundStatus;
import com.voxticket.persistence.entity.enums.ReturnStatus;
import com.voxticket.persistence.entity.enums.TicketStatus;
import com.voxticket.persistence.repository.OrderClaimRepository;
import com.voxticket.persistence.repository.OrderRepository;
import com.voxticket.persistence.repository.RefundRepository;
import com.voxticket.persistence.repository.ReturnRequestRepository;
import com.voxticket.persistence.repository.SupportTicketRepository;
import com.voxticket.persistence.repository.VerificationChallengeRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 12 (P4): read-only operations queries for the admin UI. Reuses the
 * existing commerce repositories; adds no business logic and performs no
 * writes. The verification challenge DTO never carries the OTP hash/salt.
 */
@Service
@Transactional(readOnly = true)
public class OperationsAdminService {

    private final OrderRepository orderRepository;
    private final ReturnRequestRepository returnRepository;
    private final RefundRepository refundRepository;
    private final OrderClaimRepository claimRepository;
    private final VerificationChallengeRepository challengeRepository;
    private final SupportTicketRepository ticketRepository;

    public OperationsAdminService(
            OrderRepository orderRepository,
            ReturnRequestRepository returnRepository,
            RefundRepository refundRepository,
            OrderClaimRepository claimRepository,
            VerificationChallengeRepository challengeRepository,
            SupportTicketRepository ticketRepository) {
        this.orderRepository = orderRepository;
        this.returnRepository = returnRepository;
        this.refundRepository = refundRepository;
        this.claimRepository = claimRepository;
        this.challengeRepository = challengeRepository;
        this.ticketRepository = ticketRepository;
    }

    public PageDto<OrderDto> orders(String status, String q, int page, int size) {
        OrderStatus s = parseEnum(status, OrderStatus.class);
        Page<Order> result = orderRepository.searchAdmin(
                s, blankToNull(q), pageOf(page, size, "placedAt"));
        return PageDto.of(result.map(o -> new OrderDto(
                o.getOrderNumber(),
                nameOf(o.getOrderStatus()),
                nameOf(o.getFulfillmentStatus()),
                o.getCurrency(),
                o.getTotalAmount(),
                o.getPlacedAt(),
                o.getItems() == null ? 0 : o.getItems().size())));
    }

    public PageDto<ReturnDto> returns(String status, String q, int page, int size) {
        ReturnStatus s = parseEnum(status, ReturnStatus.class);
        Page<ReturnRequest> result = returnRepository.searchAdmin(
                s, blankToNull(q), pageOf(page, size, "requestedAt"));
        return PageDto.of(result.map(r -> new ReturnDto(
                r.getReturnNumber(),
                r.getOrder() == null ? null : r.getOrder().getOrderNumber(),
                nameOf(r.getStatus()),
                nameOf(r.getReason()),
                r.getRequestedAt(),
                r.getCompletedAt())));
    }

    public PageDto<RefundDto> refunds(String status, String q, int page, int size) {
        RefundStatus s = parseEnum(status, RefundStatus.class);
        Page<Refund> result = refundRepository.searchAdmin(
                s, blankToNull(q), pageOf(page, size, "initiatedAt"));
        return PageDto.of(result.map(r -> new RefundDto(
                r.getRefundNumber(),
                r.getOrder() == null ? null : r.getOrder().getOrderNumber(),
                r.getAmount(),
                nameOf(r.getStatus()),
                nameOf(r.getReason()),
                r.getInitiatedAt(),
                r.getCompletedAt())));
    }

    public PageDto<ClaimDto> claims(String status, String q, int page, int size) {
        ClaimStatus s = parseEnum(status, ClaimStatus.class);
        Page<OrderClaim> result = claimRepository.searchAdmin(
                s, blankToNull(q), pageOf(page, size, "createdAt"));
        return PageDto.of(result.map(c -> new ClaimDto(
                c.getClaimNumber(),
                c.getOrder() == null ? null : c.getOrder().getOrderNumber(),
                nameOf(c.getReason()),
                nameOf(c.getRequestedResolution()),
                nameOf(c.getStatus()),
                c.getCreatedAt())));
    }

    public PageDto<VerificationChallengeDto> challenges(
            Boolean verified, Boolean consumed, String sessionId, int page, int size) {
        Page<VerificationChallenge> result = challengeRepository.searchAdmin(
                verified, consumed, blankToNull(sessionId), pageOf(page, size, "createdAt"));
        return PageDto.of(result.map(c -> new VerificationChallengeDto(
                c.getId() == null ? null : c.getId().toString(),
                c.getSessionId(),
                nameOf(c.getPurpose()),
                nameOf(c.getDeliveryChannel()),
                c.getMaskedDestination(),
                c.getAttempts(),
                c.isConsumed(),
                c.isVerified(),
                c.isExpired(),
                c.getCreatedAt(),
                c.getExpiresAt())));
    }

    public PageDto<EscalationDto> escalations(String status, int page, int size) {
        TicketStatus s = parseEnum(status, TicketStatus.class);
        Page<SupportTicket> result = ticketRepository.searchAdmin(s, pageOf(page, size, "createdAt"));
        return PageDto.of(result.map(t -> new EscalationDto(
                t.getTicketNumber(),
                nameOf(t.getCategory()),
                nameOf(t.getPriority()),
                nameOf(t.getStatus()),
                t.getSummary(),
                t.getCreatedAt(),
                t.getResolvedAt())));
    }

    private static Pageable pageOf(int page, int size, String sortProperty) {
        return PageRequest.of(
                Math.max(0, page),
                Math.min(Math.max(1, size), 200),
                Sort.by(Sort.Direction.DESC, sortProperty));
    }

    private static <E extends Enum<E>> E parseEnum(String value, Class<E> type) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown " + type.getSimpleName() + ": " + value);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String nameOf(Enum<?> e) {
        return e == null ? null : e.name();
    }
}
