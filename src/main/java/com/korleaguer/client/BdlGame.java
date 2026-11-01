package com.korleaguer.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.util.List;

/**
 * balldontlie MLBGame.
 * date는 문자열로 받아 둔다. 시간대 표시가 없으면 UTC로 간주한다(KoreanTime.parseUtc).
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record BdlGame(
        Long id,
        Integer season,
        String seasonType,
        Boolean postseason,
        String date,
        String venue,
        BdlTeam homeTeam,
        BdlTeam awayTeam,
        TeamData homeTeamData,
        TeamData awayTeamData,
        String status,
        String statusState) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TeamData(Integer hits, Integer runs, Integer errors, List<Integer> inningScores) {
    }
}
