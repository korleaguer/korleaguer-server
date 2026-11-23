package com.korleaguer.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

import java.time.Instant;

/**
 * 지금 시즌 MLB 전체 선수와 견준 이 선수의 위치(백분위)와 그 바탕 값. (선수, 시즌, 묶음)마다 한 줄이다(이슈 #133).
 * 수집기(LeaguePositionRefresher)가 하루 한 번 MLB 리그 전체 기록을 받아 계산해 덮어쓴다. 값은 MLB 공식 Stats API
 * sabermetrics 그대로다(우리가 기준을 만들지 않는다). 그날 리그 기록에 이 선수가 없으면(시즌 전, 마이너에만 있음)
 * 값과 위치가 모두 null이고, 줄은 '오늘 받았음' 표시로 남는다.
 * 위치(pct*)는 1~99로, 견준 선수(poolSize명) 중 이 선수보다 값이 나쁜 비율이다(높을수록 좋다). 표본이 적으면 null이다.
 */
@Entity
public class PlayerLeaguePosition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "player_id")
    private Player player;

    private int season;

    @Enumerated(EnumType.STRING)
    private PlayerSeasonMetric.Group statGroup;

    private Integer plateAppearances;
    private Integer inningsOuts;
    private Double wrcPlus;
    private Double war;
    private Double battingRuns;
    private Double baseRunningRuns;
    private Double fieldingRuns;
    private Double eraMinus;
    private Double fipMinus;
    private Double strikeoutRate;
    private Double walkRate;
    private Integer poolSize;
    private Integer pctOverall;
    private Integer pctBatting;
    private Integer pctBaseRunning;
    private Integer pctFielding;
    private Integer pctRunPrevention;
    private Integer pctStrikeouts;
    private Integer pctWalks;
    private Instant fetchedAt;

    protected PlayerLeaguePosition() {
    }

    public PlayerLeaguePosition(Player player, int season, PlayerSeasonMetric.Group group) {
        this.player = player;
        this.season = season;
        this.statGroup = group;
    }

    /** 타자 값과 위치로 덮어쓴다. null이면 그날 리그 기록에 없다(모두 비운다). */
    public void update(Hitting hitting, Instant fetchedAt) {
        clear(fetchedAt);
        if (hitting == null) {
            return;
        }
        this.plateAppearances = hitting.plateAppearances();
        this.wrcPlus = hitting.wrcPlus();
        this.war = hitting.war();
        this.battingRuns = hitting.battingRuns();
        this.baseRunningRuns = hitting.baseRunningRuns();
        this.fieldingRuns = hitting.fieldingRuns();
        this.poolSize = hitting.poolSize();
        this.pctOverall = hitting.pctOverall();
        this.pctBatting = hitting.pctBatting();
        this.pctBaseRunning = hitting.pctBaseRunning();
        this.pctFielding = hitting.pctFielding();
    }

    /** 투수 값과 위치로 덮어쓴다. null이면 그날 리그 기록에 없다(모두 비운다). */
    public void update(Pitching pitching, Instant fetchedAt) {
        clear(fetchedAt);
        if (pitching == null) {
            return;
        }
        this.inningsOuts = pitching.inningsOuts();
        this.eraMinus = pitching.eraMinus();
        this.fipMinus = pitching.fipMinus();
        this.strikeoutRate = pitching.strikeoutRate();
        this.walkRate = pitching.walkRate();
        this.war = pitching.war();
        this.poolSize = pitching.poolSize();
        this.pctOverall = pitching.pctOverall();
        this.pctRunPrevention = pitching.pctRunPrevention();
        this.pctStrikeouts = pitching.pctStrikeouts();
        this.pctWalks = pitching.pctWalks();
    }

    private void clear(Instant fetchedAt) {
        plateAppearances = null;
        inningsOuts = null;
        wrcPlus = null;
        war = null;
        battingRuns = null;
        baseRunningRuns = null;
        fieldingRuns = null;
        eraMinus = null;
        fipMinus = null;
        strikeoutRate = null;
        walkRate = null;
        poolSize = null;
        pctOverall = null;
        pctBatting = null;
        pctBaseRunning = null;
        pctFielding = null;
        pctRunPrevention = null;
        pctStrikeouts = null;
        pctWalks = null;
        this.fetchedAt = fetchedAt;
    }

    /**
     * 타자 값과 위치. fieldingRuns는 MLB fielding + positional(포지션 난도 보정)이다.
     * 위치는 종합(WAR/타석), 타격(wRC+), 주루·수비(타석당 득점)다.
     */
    public record Hitting(Integer plateAppearances, Double wrcPlus, Double war, Double battingRuns,
                          Double baseRunningRuns, Double fieldingRuns, Integer poolSize, Integer pctOverall,
                          Integer pctBatting, Integer pctBaseRunning, Integer pctFielding) {
    }

    /**
     * 투수 값과 위치. strikeoutRate·walkRate는 상대 타자 중 삼진·볼넷 비율(%, 0~100)이다.
     * 위치는 종합(WAR/아웃), 실점 억제(ERA-, 낮을수록 좋다), 삼진(K%), 볼넷(BB%, 낮을수록 좋다)이다.
     */
    public record Pitching(Integer inningsOuts, Double eraMinus, Double fipMinus, Double strikeoutRate,
                           Double walkRate, Double war, Integer poolSize, Integer pctOverall, Integer pctRunPrevention,
                           Integer pctStrikeouts, Integer pctWalks) {
    }

    public Long getId() {
        return id;
    }

    public Player getPlayer() {
        return player;
    }

    public int getSeason() {
        return season;
    }

    public PlayerSeasonMetric.Group getStatGroup() {
        return statGroup;
    }

    /** 리그 기록에 이 선수가 있었나(없으면 모든 값이 null) */
    public boolean inLeague() {
        return plateAppearances != null || inningsOuts != null;
    }

    public Integer getPlateAppearances() {
        return plateAppearances;
    }

    public Integer getInningsOuts() {
        return inningsOuts;
    }

    public Double getWrcPlus() {
        return wrcPlus;
    }

    public Double getWar() {
        return war;
    }

    public Double getBattingRuns() {
        return battingRuns;
    }

    public Double getBaseRunningRuns() {
        return baseRunningRuns;
    }

    public Double getFieldingRuns() {
        return fieldingRuns;
    }

    public Double getEraMinus() {
        return eraMinus;
    }

    public Double getFipMinus() {
        return fipMinus;
    }

    public Double getStrikeoutRate() {
        return strikeoutRate;
    }

    public Double getWalkRate() {
        return walkRate;
    }

    public Integer getPoolSize() {
        return poolSize;
    }

    public Integer getPctOverall() {
        return pctOverall;
    }

    public Integer getPctBatting() {
        return pctBatting;
    }

    public Integer getPctBaseRunning() {
        return pctBaseRunning;
    }

    public Integer getPctFielding() {
        return pctFielding;
    }

    public Integer getPctRunPrevention() {
        return pctRunPrevention;
    }

    public Integer getPctStrikeouts() {
        return pctStrikeouts;
    }

    public Integer getPctWalks() {
        return pctWalks;
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }
}
