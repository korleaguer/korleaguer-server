package com.korleaguer.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

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
     *   <li>yearByYear 개수 기록(한국인 역대 기록, 이슈 #177): 타격의 hits·rbi·stolenBases, 투구의 wins·saves</li>
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
            Double fipMinus,
            Integer hits,
            Integer rbi,
            Integer stolenBases,
            Integer wins,
            Integer saves) {

        /** 개수 기록(hits·rbi·stolenBases·wins·saves)이 없는 줄 */
        public Stat(Integer plateAppearances, String inningsPitched, Integer gamesStarted, Double wRcPlus,
                    Double eraMinus, Double war, Integer gamesPlayed, Integer homeRuns, Integer strikeOuts,
                    Integer baseOnBalls, Integer battersFaced, String avg, String ops, String era, Double batting,
                    Double baseRunning, Double fielding, Double positional, Double fipMinus) {
            this(plateAppearances, inningsPitched, gamesStarted, wRcPlus, eraMinus, war, gamesPlayed, homeRuns,
                    strikeOuts, baseOnBalls, battersFaced, avg, ops, era, batting, baseRunning, fielding, positional,
                    fipMinus, null, null, null, null, null);
        }

        /** 시즌 성적표용 칸(합계·wRC+·ERA-·WAR)만 있는 줄 */
        public Stat(Integer plateAppearances, String inningsPitched, Integer gamesStarted, Double wRcPlus,
                    Double eraMinus, Double war) {
            this(plateAppearances, inningsPitched, gamesStarted, wRcPlus, eraMinus, war, null, null, null, null, null,
                    null, null, null, null, null, null, null, null);
        }
    }

    /** 시즌 연도. 없거나 숫자가 아니면 null */
    public Integer seasonYear() {
        try {
            return season == null ? null : Integer.valueOf(season);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 여러 팀 시즌을 합친 줄인지(팀이 없고 numTeams가 있다) */
    public boolean combined() {
        return numTeams != null && team == null;
    }

    /** 선수 한 명의 줄들 → 시즌마다 받은 줄(오래된 시즌부터). 기록이나 연도가 없는 줄은 뺀다 */
    public static Map<Integer, List<MlbSeasonSplit>> bySeason(List<MlbSeasonSplit> splits) {
        Map<Integer, List<MlbSeasonSplit>> bySeason = new TreeMap<>();
        for (MlbSeasonSplit split : splits) {
            Integer year = split.seasonYear();
            if (split.stat() != null && year != null) {
                bySeason.computeIfAbsent(year, y -> new ArrayList<>()).add(split);
            }
        }
        return bySeason;
    }

    /**
     * 한 시즌의 줄들 가운데 그 시즌 기록으로 쓸 줄. 여러 팀 시즌은 합친 줄 하나만 쓰고, 합친 줄이 없으면 팀별 줄을 모두 쓴다
     * (부르는 쪽이 더한다, sum). 줄이 하나면 그 줄이다.
     */
    public static List<MlbSeasonSplit> pick(List<MlbSeasonSplit> lines) {
        return lines.stream().filter(MlbSeasonSplit::combined).findFirst()
                .map(List::of)
                .orElseGet(() -> lines.stream().filter(l -> !l.combined()).toList());
    }

    /** 줄들의 값을 더한다. 모든 줄에 값이 없으면 null */
    public static Integer sum(List<MlbSeasonSplit> lines, Function<Stat, Integer> value) {
        Integer total = null;
        for (MlbSeasonSplit line : lines) {
            Integer v = value.apply(line.stat());
            if (v != null) {
                total = (total == null ? 0 : total) + v;
            }
        }
        return total;
    }
}
