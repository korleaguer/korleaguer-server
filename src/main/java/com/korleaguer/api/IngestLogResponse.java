package com.korleaguer.api;

import com.korleaguer.domain.IngestKind;
import com.korleaguer.domain.IngestLog;
import com.korleaguer.domain.IngestStatus;

import java.time.Instant;

/** 관리용 수집 엔드포인트의 응답 */
public record IngestLogResponse(
        long id,
        IngestKind kind,
        IngestStatus status,
        String message,
        Integer requestsUsed,
        Instant startedAt,
        Instant finishedAt) {

    public static IngestLogResponse from(IngestLog log) {
        return new IngestLogResponse(log.getId(), log.getKind(), log.getStatus(), log.getMessage(),
                log.getRequestsUsed(), log.getStartedAt(), log.getFinishedAt());
    }
}
