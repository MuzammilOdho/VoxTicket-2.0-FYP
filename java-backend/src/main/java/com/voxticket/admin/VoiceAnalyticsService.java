package com.voxticket.admin;

import com.voxticket.admin.dto.PageDto;
import com.voxticket.admin.dto.VoiceDtos.VoiceCallDetailDto;
import com.voxticket.admin.dto.VoiceDtos.VoiceCallSummaryDto;
import com.voxticket.admin.dto.VoiceDtos.VoiceTurnMetricDto;
import com.voxticket.persistence.entity.VoiceCallSessionEntity;
import com.voxticket.persistence.entity.VoiceCallTurnMetricEntity;
import com.voxticket.persistence.repository.VoiceCallSearchRepository;
import com.voxticket.persistence.repository.VoiceCallSearchRepository.VoiceCallRow;
import com.voxticket.persistence.repository.VoiceCallSessionRepository;
import com.voxticket.persistence.repository.VoiceCallTurnMetricRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 12 (P4): read-only voice analytics. Call lists carry aggregate
 * stage latencies (STT, brain TTFT, TTS first audio, end-to-end) computed
 * from the per-turn metrics the Python worker emits; the detail view shows
 * every turn of one call.
 */
@Service
@Transactional(readOnly = true)
public class VoiceAnalyticsService {

    private final VoiceCallSearchRepository searchRepository;
    private final VoiceCallSessionRepository callRepository;
    private final VoiceCallTurnMetricRepository turnMetricRepository;
    private final JdbcTemplate jdbc;

    public VoiceAnalyticsService(
            VoiceCallSearchRepository searchRepository,
            VoiceCallSessionRepository callRepository,
            VoiceCallTurnMetricRepository turnMetricRepository,
            JdbcTemplate jdbc) {
        this.searchRepository = searchRepository;
        this.callRepository = callRepository;
        this.turnMetricRepository = turnMetricRepository;
        this.jdbc = jdbc;
    }

    public PageDto<VoiceCallSummaryDto> listCalls(String outcome, Instant from, Instant to, int page, int size) {
        Pageable pageable = PageRequest.of(
                Math.max(0, page),
                Math.min(Math.max(1, size), 200),
                Sort.by(Sort.Direction.DESC, "startedAt"));
        Page<VoiceCallRow> rows = searchRepository.search(
                blankToNull(outcome),
                from == null ? Instant.EPOCH : from,
                to == null ? Instant.now().plusSeconds(60) : to,
                pageable);
        return PageDto.of(rows.map(this::toSummary));
    }

    public VoiceCallDetailDto getCallDetail(String roomOrSessionId) {
        VoiceCallSessionEntity call = callRepository.findByRoom(roomOrSessionId)
                .or(() -> {
                    try {
                        List<VoiceCallSessionEntity> bySession = jdbc.query(
                                "SELECT v.* FROM voice_call_sessions v "
                                        + "JOIN conversation_sessions s ON s.id = v.session_id "
                                        + "WHERE s.session_id = ?",
                                (rs, n) -> callRepository.findById(UUID.fromString(rs.getString("id")))
                                        .orElse(null),
                                roomOrSessionId);
                        return bySession.stream().filter(java.util.Objects::nonNull).findFirst();
                    } catch (Exception e) {
                        return Optional.empty();
                    }
                })
                .orElseThrow(() -> new IllegalArgumentException(
                        "No voice call found for " + roomOrSessionId));
        List<VoiceCallTurnMetricEntity> turns =
                turnMetricRepository.findByCallIdOrderByTurnNumberAsc(call.getId());
        String sid = sessionIdString(call);
        VoiceCallSummaryDto summary = toSummary(call, sid, turns);
        List<VoiceTurnMetricDto> turnDtos = turns.stream()
                .map(t -> new VoiceTurnMetricDto(
                        t.getTurnNumber() == null ? 0 : t.getTurnNumber(),
                        t.getTraceId(), t.getSttLatencyMs(), t.getBrainTtftMs(),
                        t.getTtsFirstAudioMs(), t.getE2eMs(),
                        Boolean.TRUE.equals(t.getAborted()), Boolean.TRUE.equals(t.getBargeIn()),
                        t.getSttLanguage(), t.getError(), t.getCreatedAt()))
                .toList();
        return new VoiceCallDetailDto(summary, turnDtos);
    }

    private String sessionIdString(VoiceCallSessionEntity call) {
        if (call.getSessionId() == null) {
            return call.getRoom();
        }
        try {
            String sid = jdbc.queryForObject(
                    "SELECT session_id FROM conversation_sessions WHERE id = ?",
                    String.class, call.getSessionId());
            return sid == null ? call.getRoom() : sid;
        } catch (Exception e) {
            return call.getRoom();
        }
    }

    private VoiceCallSummaryDto toSummary(VoiceCallRow row) {
        return new VoiceCallSummaryDto(
                row.getSessionId(), row.getRoom(), row.getTraceId(),
                row.getSttProvider(), row.getSttModel(), row.getTtsProvider(), row.getTtsModel(),
                row.getStartedAt(), row.getEndedAt(), row.getOutcome(),
                row.getBargeInCount() == null ? 0 : row.getBargeInCount(),
                row.getDisconnectReason(), row.getWorkerId(),
                row.getTurnCount() == null ? 0L : row.getTurnCount(),
                row.getAvgSttLatencyMs(), row.getAvgBrainTtftMs(),
                row.getAvgTtsFirstAudioMs(), row.getAvgE2eMs());
    }

    private VoiceCallSummaryDto toSummary(
            VoiceCallSessionEntity call, String sessionId, List<VoiceCallTurnMetricEntity> turns) {
        return new VoiceCallSummaryDto(
                sessionId, call.getRoom(), call.getTraceId(),
                call.getSttProvider(), call.getSttModel(), call.getTtsProvider(), call.getTtsModel(),
                call.getStartedAt(), call.getEndedAt(), call.getOutcome(),
                call.getBargeInCount() == null ? 0 : call.getBargeInCount(),
                call.getDisconnectReason(), call.getWorkerId(),
                turns.size(),
                avg(turns, VoiceCallTurnMetricEntity::getSttLatencyMs),
                avg(turns, VoiceCallTurnMetricEntity::getBrainTtftMs),
                avg(turns, VoiceCallTurnMetricEntity::getTtsFirstAudioMs),
                avg(turns, VoiceCallTurnMetricEntity::getE2eMs));
    }

    private static Double avg(List<VoiceCallTurnMetricEntity> turns,
            Function<VoiceCallTurnMetricEntity, Double> f) {
        double sum = 0;
        int n = 0;
        for (VoiceCallTurnMetricEntity t : turns) {
            Double v = f.apply(t);
            if (v != null) {
                sum += v;
                n++;
            }
        }
        return n == 0 ? null : sum / n;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
