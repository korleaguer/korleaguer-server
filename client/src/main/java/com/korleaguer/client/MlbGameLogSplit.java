package com.korleaguer.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 선수의 경기 한 번(people/{id}/stats?stats=gameLog의 splits 한 줄).
 * date는 그 경기의 공식 날짜(미국 날짜), gameType은 R(정규시즌) 등, team은 그 경기에서 뛴 팀이다.
 * sport는 그 경기의 리그 단계다(마이너 gameLog에만 온다. id 11 = 트리플A ~ 16 = 루키, abbreviation은 "AAA" 등).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MlbGameLogSplit(
        String season,
        String date,
        String gameType,
        Boolean isHome,
        Boolean isWin,
        MlbTeam team,
        MlbTeam opponent,
        GameRef game,
        MlbPerson player,
        MlbStat stat,
        Sport sport) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GameRef(Long gamePk) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Sport(Long id, String abbreviation) {
    }

    /** sport.id. 없으면 null */
    public Long sportId() {
        return sport == null ? null : sport.id();
    }

    /** gamePk. 없으면 null */
    public Long gamePk() {
        return game == null ? null : game.gamePk();
    }
}
