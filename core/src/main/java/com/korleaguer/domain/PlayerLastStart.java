package com.korleaguer.domain;

import java.time.Instant;

/** 선수 한 명이 뛴 마지막 경기의 시작 시각(AppearanceRepository.findLastStartTimes 결과) */
public record PlayerLastStart(Long playerId, Instant startTimeUtc) {
}
