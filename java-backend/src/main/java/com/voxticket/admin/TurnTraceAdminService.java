package com.voxticket.admin;

import com.voxticket.admin.dto.TurnTraceDto;
import com.voxticket.persistence.entity.TurnTraceRecordEntity;
import com.voxticket.persistence.repository.TurnTraceRecordRepository;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Phase 12 (P4): read-only per-turn AI decision trace queries. Maps the
 * {@code conversation_turn_traces} row back to the {@code TurnTrace} shape;
 * JSONB columns are parsed best-effort (a malformed payload yields an empty
 * list/map, never a failed request).
 */
@Service
@Transactional(readOnly = true)
public class TurnTraceAdminService {

    private static final Logger log = LoggerFactory.getLogger(TurnTraceAdminService.class);
    private static final TypeReference<List<Map<String, Object>>> LIST_OF_MAPS =
            new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final TurnTraceRecordRepository repository;
    private final ObjectMapper objectMapper;

    public TurnTraceAdminService(TurnTraceRecordRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public TurnTraceDto getByTraceId(String traceId) {
        TurnTraceRecordEntity t = repository.findByTraceId(traceId)
                .orElseThrow(() -> new IllegalArgumentException("No turn trace found for traceId " + traceId));
        return toDto(t);
    }

    private TurnTraceDto toDto(TurnTraceRecordEntity t) {
        String sessionId = t.getSession() != null ? t.getSession().getSessionId() : null;
        List<TurnTraceDto.RagDoc> ragDocs = parseList(t.getRagDocs()).stream()
                .map(m -> new TurnTraceDto.RagDoc(
                        str(m.get("docId")),
                        str(m.get("category")),
                        num(m.get("similarity"))))
                .toList();
        List<TurnTraceDto.ToolCall> tools = parseList(t.getTools()).stream()
                .map(m -> new TurnTraceDto.ToolCall(
                        str(m.get("name")),
                        str(m.get("result")),
                        num(m.get("durationMs"))))
                .toList();
        return new TurnTraceDto(
                sessionId,
                t.getTurnNumber(),
                t.getChannel() == null ? null : t.getChannel().toLowerCase(java.util.Locale.ROOT),
                t.getTraceId(),
                t.getParentTraceId(),
                t.getStartedAt(),
                t.getEndedAt(),
                t.getOutcome(),
                t.isAborted(),
                t.getNormalizeMs(),
                t.getGuardMs(),
                t.getRoutingMs(),
                t.getLlmTtftMs(),
                t.getLlmTotalMs(),
                t.getRagMs(),
                t.getToolMs(),
                t.getGuardSuspicious(),
                t.getGuardCategory(),
                t.getGuardImplementation(),
                t.getGuardFallback(),
                t.getLanguage(),
                t.getIntent(),
                splitSignals(t.getIntentSignals()),
                t.getRoutingStrategy(),
                t.getTier(),
                t.getRoutingReason(),
                t.getSemanticMargin(),
                t.getProvider(),
                t.getModel(),
                t.getPromptTokens(),
                t.getCompletionTokens(),
                t.getRagCacheHit(),
                ragDocs,
                tools,
                t.getProcedureType(),
                t.getProcedureStatus(),
                t.getProcedureOutcomeCode(),
                t.getErrorCode(),
                parseMap(t.getDecision()));
    }

    private static List<String> splitSignals(String signals) {
        if (signals == null || signals.isBlank()) {
            return null;
        }
        return java.util.Arrays.stream(signals.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private List<Map<String, Object>> parseList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, LIST_OF_MAPS);
        } catch (Exception e) {
            log.warn("event=turn_trace_json_parse_failed traceParse=ragDocs/tools errorType={}",
                    e.getClass().getSimpleName());
            return List.of();
        }
    }

    private Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            log.warn("event=turn_trace_json_parse_failed traceParse=decision errorType={}",
                    e.getClass().getSimpleName());
            return Map.of();
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static double num(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        return 0.0;
    }
}
