package com.voxticket.admin;

import com.voxticket.admin.dto.AdminSummaryV2;
import com.voxticket.admin.dto.AiAnalyticsDtos.AuditEventDto;
import com.voxticket.admin.dto.AiAnalyticsDtos.CostAnalyticsDto;
import com.voxticket.admin.dto.AiAnalyticsDtos.EvaluationSummaryDto;
import com.voxticket.admin.dto.AiAnalyticsDtos.ModelAnalyticsDto;
import com.voxticket.admin.dto.AiAnalyticsDtos.RagAnalyticsDto;
import com.voxticket.admin.dto.AiAnalyticsDtos.RoutingAnalyticsDto;
import com.voxticket.admin.dto.AiAnalyticsDtos.SystemHealthDto;
import com.voxticket.admin.dto.ConversationDetailDto;
import com.voxticket.admin.dto.ConversationSummaryDto;
import com.voxticket.admin.dto.OperationDtos.ClaimDto;
import com.voxticket.admin.dto.OperationDtos.EscalationDto;
import com.voxticket.admin.dto.OperationDtos.OrderDto;
import com.voxticket.admin.dto.OperationDtos.RefundDto;
import com.voxticket.admin.dto.OperationDtos.ReturnDto;
import com.voxticket.admin.dto.OperationDtos.VerificationChallengeDto;
import com.voxticket.admin.dto.PageDto;
import com.voxticket.admin.dto.TurnTraceDto;
import com.voxticket.admin.dto.VoiceDtos.VoiceCallDetailDto;
import com.voxticket.admin.dto.VoiceDtos.VoiceCallSummaryDto;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 12 (P4): read-only admin API for the React admin frontend.
 *
 * <p>Profile-gated like the existing admin surface ({@code dev}/{@code test})
 * and additionally guarded by the {@code ADMIN} role via
 * {@code AdminSecurityConfig} - the profile gate is NOT access control.
 * Every endpoint is read-only: no business write operation is exposed here.
 *
 * <p>The pre-existing {@link AdminController} is left untouched; its three
 * endpoints keep their exact behavior.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Profile({"dev", "test"})
public class AdminApiController {

    private final AdminSummaryService summaryService;
    private final ConversationAdminService conversationService;
    private final TurnTraceAdminService turnTraceService;
    private final OperationsAdminService operationsService;
    private final VoiceAnalyticsService voiceService;
    private final AuditAdminService auditService;
    private final AiAnalyticsService aiAnalyticsService;
    private final EvaluationService evaluationService;
    private final SystemHealthService healthService;

    public AdminApiController(
            AdminSummaryService summaryService,
            ConversationAdminService conversationService,
            TurnTraceAdminService turnTraceService,
            OperationsAdminService operationsService,
            VoiceAnalyticsService voiceService,
            AuditAdminService auditService,
            AiAnalyticsService aiAnalyticsService,
            EvaluationService evaluationService,
            SystemHealthService healthService) {
        this.summaryService = summaryService;
        this.conversationService = conversationService;
        this.turnTraceService = turnTraceService;
        this.operationsService = operationsService;
        this.voiceService = voiceService;
        this.auditService = auditService;
        this.aiAnalyticsService = aiAnalyticsService;
        this.evaluationService = evaluationService;
        this.healthService = healthService;
    }

    // ---- Dashboard ----

    @GetMapping("/summary/v2")
    public AdminSummaryV2 summaryV2() {
        return summaryService.getSummary();
    }

    // ---- Conversations ----

    @GetMapping("/conversations/search")
    public PageDto<ConversationSummaryDto> searchConversations(
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) Boolean escalated,
            @RequestParam(required = false, name = "q") String query,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return conversationService.searchConversations(
                channel, outcome, escalated, query, from, to, page, size);
    }

    @GetMapping("/conversations/{sessionId}/detail")
    public ResponseEntity<ConversationDetailDto> conversationDetail(@PathVariable String sessionId) {
        try {
            return ResponseEntity.ok(conversationService.getDetail(sessionId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ---- Turn decision trace ----

    @GetMapping("/turns/{traceId}")
    public ResponseEntity<TurnTraceDto> turnTrace(@PathVariable String traceId) {
        try {
            return ResponseEntity.ok(turnTraceService.getByTraceId(traceId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ---- Operations (read-only) ----

    @GetMapping("/operations/orders")
    public PageDto<OrderDto> orders(
            @RequestParam(required = false) String status,
            @RequestParam(required = false, name = "q") String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return operationsService.orders(status, query, page, size);
    }

    @GetMapping("/operations/returns")
    public PageDto<ReturnDto> returns(
            @RequestParam(required = false) String status,
            @RequestParam(required = false, name = "q") String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return operationsService.returns(status, query, page, size);
    }

    @GetMapping("/operations/refunds")
    public PageDto<RefundDto> refunds(
            @RequestParam(required = false) String status,
            @RequestParam(required = false, name = "q") String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return operationsService.refunds(status, query, page, size);
    }

    @GetMapping("/operations/claims")
    public PageDto<ClaimDto> claims(
            @RequestParam(required = false) String status,
            @RequestParam(required = false, name = "q") String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return operationsService.claims(status, query, page, size);
    }

    @GetMapping("/operations/verification")
    public PageDto<VerificationChallengeDto> verification(
            @RequestParam(required = false) Boolean verified,
            @RequestParam(required = false) Boolean consumed,
            @RequestParam(required = false) String sessionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return operationsService.challenges(verified, consumed, sessionId, page, size);
    }

    @GetMapping("/operations/challenges")
    public PageDto<VerificationChallengeDto> challenges(
            @RequestParam(required = false) Boolean verified,
            @RequestParam(required = false) Boolean consumed,
            @RequestParam(required = false) String sessionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return operationsService.challenges(verified, consumed, sessionId, page, size);
    }

    @GetMapping("/operations/escalations")
    public PageDto<EscalationDto> escalations(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return operationsService.escalations(status, page, size);
    }

    // ---- Voice analytics ----

    @GetMapping("/voice/calls")
    public PageDto<VoiceCallSummaryDto> voiceCalls(
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return voiceService.listCalls(outcome, from, to, page, size);
    }

    @GetMapping("/voice/calls/{sessionId}")
    public ResponseEntity<VoiceCallDetailDto> voiceCallDetail(@PathVariable String sessionId) {
        try {
            return ResponseEntity.ok(voiceService.getCallDetail(sessionId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ---- Audit log ----

    @GetMapping("/audit/events")
    public PageDto<AuditEventDto> auditEvents(
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return auditService.searchEvents(sessionId, type, from, to, page, size);
    }

    // ---- AI analytics ----

    @GetMapping("/ai/models")
    public ModelAnalyticsDto aiModels() {
        return aiAnalyticsService.modelAnalytics();
    }

    @GetMapping("/ai/routing")
    public RoutingAnalyticsDto aiRouting() {
        return aiAnalyticsService.routingAnalytics();
    }

    @GetMapping("/ai/rag")
    public RagAnalyticsDto aiRag() {
        return aiAnalyticsService.ragAnalytics();
    }

    @GetMapping("/ai/cost")
    public CostAnalyticsDto aiCost() {
        return aiAnalyticsService.costAnalytics();
    }

    // ---- Evaluation ----

    @GetMapping("/evaluation/summary")
    public EvaluationSummaryDto evaluationSummary() {
        return evaluationService.getSummary();
    }

    // ---- System health ----

    @GetMapping("/system/health")
    public SystemHealthDto systemHealth() {
        return healthService.getHealth();
    }
}
