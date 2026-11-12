package com.korleaguer.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 선수의 한 시즌 합계 한 줄(people/{id}/stats?stats=yearByYear 또는 stats=sabermetrics의 splits 한 줄).
 * 한 시즌에 여러 팀에서 뛰었으면 팀마다 한 줄과 합친 줄(team 없음, numTeams = 팀 수)이 온다.
 * player·group은 샘플 데이터에서 선수·묶음을 가를 때만 쓴다(MLB 응답의 split에는 group이 없다).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MlbSeasonSplit(
        String season,
        Integer numTeams,
        MlbTeam team,
        MlbPerson player,
        MlbResponses.GroupRef group,
        Stat stat) {

    /**
     * 합계와 지표. yearByYear에는 plateAppearances·inningsPitched("50.2")·gamesStarted가,
     * sabermetrics에는 wRcPlus(타격)·eraMinus(투구)·war가 온다. 모든 칸은 null일 수 있다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Stat(
            Integer plateAppearances,
            String inningsPitched,
            Integer gamesStarted,
            Double wRcPlus,
            Double eraMinus,
            Double war) {
    }

    /** 여러 팀 시즌을 합친 줄인지(팀이 없고 numTeams가 있다) */
    public boolean combined() {
        return numTeams != null && team == null;
    }
}
