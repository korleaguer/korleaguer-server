package com.korleaguer.api.screen;

import org.jspecify.annotations.Nullable;

import java.time.LocalDate;

/**
 * 띠 구간(양 끝 날짜 포함). 부상 구간의 끝은 해제일 전날이다.
 *
 * @param levelKo 마이너 단계 이름(트리플A 등). 부상 구간은 있던 단계. 모르거나 메이저·팀 없음이면 null
 * @param teamKo  그 구간의 팀(이동 기록의 팀 이름, 마이너는 "오클라호마시티"처럼 지명만). 모르거나 팀 없음이면 null
 */
public record TimelineSegment(
        LocalDate from,
        LocalDate to,
        TimelineKind kind,
        @Nullable String levelKo,
        @Nullable String teamKo) {
}
