package com.korleaguer.domain;

import jakarta.persistence.Embeddable;

/** 한 경기 투구 기록. 이닝은 아웃 수(pitchingOuts)로 저장한다. */
@Embeddable
public record Pitching(
        Integer pitchingOuts,
        Integer hitsAllowed,
        Integer runsAllowed,
        Integer earnedRuns,
        Integer walksAllowed,
        Integer pitcherStrikeouts,
        Integer homeRunsAllowed,
        Integer pitchCount,
        Integer wins,
        Integer losses,
        Integer saves) {
}
