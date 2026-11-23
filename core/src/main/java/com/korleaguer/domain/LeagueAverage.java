package com.korleaguer.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.Instant;
import java.util.Map;

/**
 * 리그 단계의 한 시즌 평균(이슈 #175·#233). (시즌, 단계)마다 한 줄이다.
 * 수집기(ingest.LeagueAverageRefresher)가 MLB 팀별 시즌 합계를 더해 덮어쓰고, 화면용 API(api.LeagueEnvironments)는 이 줄만 읽는다.
 * 값은 반올림하지 않은 계산 값 그대로다.
 */
@Entity
public class LeagueAverage {

    /** 메이저 단계 이름 */
    public static final String MLB = "MLB";
    /** 리그 평균을 두는 단계(약어 → MLB 공식 API sportId). 마이너 약어는 MinorGameLog.level과 같다 */
    public static final Map<String, Integer> SPORT_IDS = Map.of(MLB, 1, "AAA", 11, "AA", 12, "A+", 13, "A", 14, "ROK", 16);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private int season;
    private String level;
    private double woba;
    private double runsPerPa;
    private double era;
    private Instant fetchedAt;

    protected LeagueAverage() {
    }

    public LeagueAverage(int season, String level) {
        this.season = season;
        this.level = level;
    }

    /**
     * 리그 평균 값 셋.
     *
     * @param woba      리그 wOBA(support.Woba 가중치로 계산)
     * @param runsPerPa 타석당 득점
     * @param era       평균자책점
     */
    public record Values(double woba, double runsPerPa, double era) {
    }

    /** 새로 받은 값으로 덮어쓴다 */
    public void update(Values values, Instant fetchedAt) {
        this.woba = values.woba();
        this.runsPerPa = values.runsPerPa();
        this.era = values.era();
        this.fetchedAt = fetchedAt;
    }

    public Values values() {
        return new Values(woba, runsPerPa, era);
    }

    public int getSeason() {
        return season;
    }

    public String getLevel() {
        return level;
    }

    /** 마지막으로 받은 시각 */
    public Instant getFetchedAt() {
        return fetchedAt;
    }
}
