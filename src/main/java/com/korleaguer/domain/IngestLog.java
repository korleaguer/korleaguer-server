package com.korleaguer.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.Instant;

/** 수집 실행 기록 */
@Entity
public class IngestLog {

    private static final int MAX_MESSAGE_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private IngestKind kind;

    private Instant startedAt;
    private Instant finishedAt;

    @Enumerated(EnumType.STRING)
    private IngestStatus status;

    private String message;
    private Integer requestsUsed;

    protected IngestLog() {
    }

    public static IngestLog start(IngestKind kind, Instant now) {
        IngestLog log = new IngestLog();
        log.kind = kind;
        log.startedAt = now;
        log.status = IngestStatus.RUNNING;
        return log;
    }

    public void finish(IngestStatus status, String message, int requestsUsed, Instant now) {
        this.status = status;
        this.message = message != null && message.length() > MAX_MESSAGE_LENGTH
                ? message.substring(0, MAX_MESSAGE_LENGTH)
                : message;
        this.requestsUsed = requestsUsed;
        this.finishedAt = now;
    }

    public Long getId() {
        return id;
    }

    public IngestKind getKind() {
        return kind;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public IngestStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public Integer getRequestsUsed() {
        return requestsUsed;
    }
}
