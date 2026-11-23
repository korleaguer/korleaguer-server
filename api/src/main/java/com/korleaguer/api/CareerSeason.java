package com.korleaguer.api;

import com.korleaguer.domain.PlayerCareerSeason;
import com.korleaguer.domain.PlayerSeasonMetric;
import com.korleaguer.support.BaseballMath;

import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 선수 화면의 지난 시즌 표 한 줄(GET /api/players/{id}의 seasons, 최신 시즌부터, 이슈 #133). MLB 정규시즌 기록만 있다(KBO 기록 없음).
 * 타자면 Hitter, 투수면 Pitcher 모양이다. 한 시즌 여러 팀이면 합친 한 줄이고 teamKo는 뛴 순서로 "탬파베이·애틀랜타"처럼 잇는다.
 * 모르는 값은 null이다. wRC+·ERA-·WAR는 MLB sabermetrics 값(소수 첫째 자리)이다.
 */
public sealed interface CareerSeason permits CareerSeason.Hitter, CareerSeason.Pitcher {

    static CareerSeason of(PlayerCareerSeason row) {
        String teamKo = row.getTeamIds().stream()
                .map(id -> TeamLabels.mlbTeam(id, null))
                .filter(Objects::nonNull)
                .collect(Collectors.collectingAndThen(Collectors.joining("·"), s -> s.isEmpty() ? null : s));
        if (row.getStatGroup() == PlayerSeasonMetric.Group.PITCHING) {
            return new Pitcher(row.getSeason(), teamKo, row.getGames(), row.getInningsOuts(), row.getEra(),
                    row.getStrikeouts(), BaseballMath.round(row.getEraMinus(), 1), BaseballMath.round(row.getWar(), 1));
        }
        return new Hitter(row.getSeason(), teamKo, row.getGames(), row.getPlateAppearances(), row.getBattingAvg(),
                row.getHomeRuns(), row.getOps(), BaseballMath.round(row.getWrcPlus(), 1),
                BaseballMath.round(row.getWar(), 1));
    }

    /** 타자 한 시즌: 경기·타석·타율·홈런·OPS·wRC+·WAR */
    record Hitter(int season, String teamKo, Integer games, Integer plateAppearances, Double avg, Integer homeRuns,
                  Double ops, Double wrcPlus, Double war) implements CareerSeason {
    }

    /** 투수 한 시즌: 경기·이닝(아웃 수)·평균자책점·삼진·ERA-·WAR */
    record Pitcher(int season, String teamKo, Integer games, Integer inningsOuts, Double era, Integer strikeouts,
                   Double eraMinus, Double war) implements CareerSeason {
    }
}
