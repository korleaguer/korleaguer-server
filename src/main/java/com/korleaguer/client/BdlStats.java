package com.korleaguer.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** balldontlie MLBStats(선수 한 명의 경기 한 번 기록). 모든 기록 칸은 null일 수 있다. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record BdlStats(
        BdlPlayer player,
        BdlTeam team,
        Long gameId,
        // 타격
        Integer atBats,
        Integer runs,
        Integer hits,
        Integer rbi,
        Integer hr,
        Integer bb,
        Integer k,
        Integer doubles,
        Integer triples,
        Integer hitByPitch,
        Integer stolenBases,
        Integer plateAppearances,
        Integer totalBases,
        Integer sacFlies,
        // 투구
        Double ip,
        Integer pHits,
        Integer pRuns,
        Integer er,
        Integer pBb,
        Integer pK,
        Integer pHr,
        Integer pitchCount,
        Integer pitchingOuts,
        Integer wins,
        Integer losses,
        Integer saves) {
}
