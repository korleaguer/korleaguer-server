package com.korleaguer.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 선수의 한 시즌 합계 한 줄(people/{id}/stats?stats=yearByYear 또는 stats=sabermetrics의 splits 한 줄,
 * 또는 리그 전체 /stats?stats=season·sabermetrics&playerPool=ALL의 splits 한 줄).
 * 선수 한 명을 물으면 여러 팀에서 뛴 시즌에 팀마다 한 줄과 합친 줄(team 없음, numTeams = 팀 수)이 온다.
 * 리그 전체를 물으면 선수마다 합친 한 줄만 온다(numTeams = 팀 수, team = 마지막 팀). player로 선수를 가른다.
 * group은 샘플 데이터에서 묶음을 가를 때만 쓴다(MLB 응답의 split에는 group이 없다).
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
     * 합계와 지표. 모든 칸은 null일 수 있다.
     * <ul>
     *   <li>yearByYear·season: plateAppearances·inningsPitched("50.2")·gamesStarted·gamesPlayed·homeRuns·strikeOuts·
     *       baseOnBalls·battersFaced(투구)와 글자로 오는 비율 avg(".258")·ops·era("3.23"). 값이 없으면 ".---"처럼 온다.</li>
     *   <li>sabermetrics: wRcPlus·war, 타격의 batting(타격 득점)·baseRunning(주루 득점)·fielding(수비 득점)·
     *       positional(포지션 보정 득점), 투구의 eraMinus·fipMinus</li>
     * </ul>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Stat(
            Integer plateAppearances,
            String inningsPitched,
            Integer gamesStarted,
            Double wRcPlus,
            Double eraMinus,
            Double war,
            Integer gamesPlayed,
            Integer homeRuns,
            Integer strikeOuts,
            Integer baseOnBalls,
            Integer battersFaced,
            String avg,
            String ops,
            String era,
            Double batting,
            Double baseRunning,
            Double fielding,
            Double positional,
            Double fipMinus) {

        /** 시즌 성적표용 칸(합계·wRC+·ERA-·WAR)만 있는 줄 */
        public Stat(Integer plateAppearances, String inningsPitched, Integer gamesStarted, Double wRcPlus,
                    Double eraMinus, Double war) {
            this(plateAppearances, inningsPitched, gamesStarted, wRcPlus, eraMinus, war, null, null, null, null, null,
                    null, null, null, null, null, null, null, null);
        }
    }

    /** 여러 팀 시즌을 합친 줄인지(팀이 없고 numTeams가 있다) */
    public boolean combined() {
        return numTeams != null && team == null;
    }
}
