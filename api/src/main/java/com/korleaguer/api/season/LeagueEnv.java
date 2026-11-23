package com.korleaguer.api.season;

import com.korleaguer.domain.LeagueAverage;

import java.util.Map;

/**
 * 리그 평균 한 줄(이슈 #175). 성적표 달별 wRC+·ERA+가 '그 리그 평균 = 100'이 되게 쓰는 값이다.
 * DB에서 읽는 일은 LeagueEnvironments(빈)가 하고, 계산 클래스는 이 값만 받는다.
 *
 * @param woba      리그 wOBA(support.Woba 가중치로 계산)
 * @param runsPerPa 타석당 득점
 * @param era       평균자책점
 */
public record LeagueEnv(double woba, double runsPerPa, double era) {

    /** 저장된 줄이 없을 때 쓰는 표준값(FanGraphs 근사. 웹 src/lib/wrcTrend.ts와 같다) */
    public static final LeagueEnv STANDARD = new LeagueEnv(0.310, 0.117, 4.20);
    /** 메이저 단계 이름(성적표 GameDay.level이 null인 경기) */
    public static final String MLB = LeagueAverage.MLB;
    /** 리그 평균을 두는 단계(약어 → MLB 공식 API sportId) */
    public static final Map<String, Integer> SPORT_IDS = LeagueAverage.SPORT_IDS;
}
