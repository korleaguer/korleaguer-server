package com.korleaguer.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.Instant;

/**
 * 한국인 메이저리거의 MLB 한 시즌 개수 기록(기록 탭, 이슈 #177). (MLB 선수 번호, 시즌, 묶음)마다 한 줄이다.
 * 지금 명단에 없는 선수(은퇴·국내 복귀)도 있어 Player가 아니라 MLB 번호로 잇는다.
 * 수집기(KoreanRecordsRefresher)가 MLB yearByYear를 받아 덮어쓴다. 한 시즌 여러 팀이면 합친 줄 하나다. 모르는 값은 null이다.
 */
@Entity
public class KoreanMlbSeason {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private long mlbId;

    private int season;

    @Enumerated(EnumType.STRING)
    private PlayerSeasonMetric.Group statGroup;

    private Integer games;
    private Integer hits;
    private Integer homeRuns;
    private Integer rbi;
    private Integer stolenBases;
    private Integer wins;
    private Integer saves;
    private Integer strikeouts;
    private Instant fetchedAt;

    protected KoreanMlbSeason() {
    }

    public KoreanMlbSeason(long mlbId, int season, PlayerSeasonMetric.Group group) {
        this.mlbId = mlbId;
        this.season = season;
        this.statGroup = group;
    }

    public void update(Counts counts, Instant fetchedAt) {
        this.games = counts.games();
        this.hits = counts.hits();
        this.homeRuns = counts.homeRuns();
        this.rbi = counts.rbi();
        this.stolenBases = counts.stolenBases();
        this.wins = counts.wins();
        this.saves = counts.saves();
        this.strikeouts = counts.strikeouts();
        this.fetchedAt = fetchedAt;
    }

    /** 한 시즌 개수 기록. 타자는 games·hits·homeRuns·rbi·stolenBases, 투수는 games·wins·saves·strikeouts를 쓴다 */
    public record Counts(Integer games, Integer hits, Integer homeRuns, Integer rbi, Integer stolenBases, Integer wins,
                         Integer saves, Integer strikeouts) {
    }

    public Counts counts() {
        return new Counts(games, hits, homeRuns, rbi, stolenBases, wins, saves, strikeouts);
    }

    public long getMlbId() {
        return mlbId;
    }

    public int getSeason() {
        return season;
    }

    public PlayerSeasonMetric.Group getStatGroup() {
        return statGroup;
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }
}
