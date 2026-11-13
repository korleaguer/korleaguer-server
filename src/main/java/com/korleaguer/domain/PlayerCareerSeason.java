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
import java.util.Arrays;
import java.util.List;

/**
 * 선수의 MLB 한 시즌 기록(선수 화면의 지난 시즌 표, 이슈 #133). (선수, 시즌, 묶음)마다 한 줄이다.
 * 수집기(LeaguePositionRefresher)가 MLB yearByYear를 하루 한 번 받아 합계를 덮어쓴다. 한 시즌 여러 팀이면 합친 줄 하나다.
 * 지표(wRC+·ERA-·WAR)는 지금 시즌이면 리그 전체 sabermetrics에서 매일, 지난 시즌이면 선수별 sabermetrics를 한 번만 물어 채운다
 * (지금 시즌이던 줄은 시즌이 지난 뒤 처음 한 번 묻는다).
 * 모르는 값은 null이다.
 */
@Entity
public class PlayerCareerSeason {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "player_id")
    private Player player;

    private int season;

    @Enumerated(EnumType.STRING)
    private PlayerSeasonMetric.Group statGroup;

    private String teamIds;
    private Integer games;
    private Integer plateAppearances;
    private Double battingAvg;
    private Integer homeRuns;
    private Double ops;
    private Integer inningsOuts;
    private Double era;
    private Integer strikeouts;
    private Double wrcPlus;
    private Double eraMinus;
    private Double war;
    private Instant fetchedAt;
    private Instant metricsFetchedAt;

    protected PlayerCareerSeason() {
    }

    public PlayerCareerSeason(Player player, int season, PlayerSeasonMetric.Group group) {
        this.player = player;
        this.season = season;
        this.statGroup = group;
    }

    public void updateTotals(Totals totals, Instant fetchedAt) {
        this.teamIds = totals.teamIds().isEmpty() ? null
                : String.join(",", totals.teamIds().stream().map(String::valueOf).toList());
        this.games = totals.games();
        this.plateAppearances = totals.plateAppearances();
        this.battingAvg = totals.battingAvg();
        this.homeRuns = totals.homeRuns();
        this.ops = totals.ops();
        this.inningsOuts = totals.inningsOuts();
        this.era = totals.era();
        this.strikeouts = totals.strikeouts();
        this.fetchedAt = fetchedAt;
    }

    /**
     * 지표를 덮어쓴다.
     *
     * @param askedAt 지난 시즌 지표를 선수별로 물은 때. 지금 시즌 값(리그 전체에서 매일 바뀜)이면 null로 두어
     *                시즌이 지난 뒤 한 번 다시 묻게 한다
     */
    public void updateMetrics(Double wrcPlus, Double eraMinus, Double war, Instant askedAt) {
        this.wrcPlus = wrcPlus;
        this.eraMinus = eraMinus;
        this.war = war;
        this.metricsFetchedAt = askedAt;
    }

    /**
     * 한 시즌 합계. teamIds는 뛴 MLB 구단 번호(뛴 순서). 타자는 games·plateAppearances·battingAvg·homeRuns·ops,
     * 투수는 games·inningsOuts·era·strikeouts만 쓴다.
     */
    public record Totals(List<Long> teamIds, Integer games, Integer plateAppearances, Double battingAvg,
                         Integer homeRuns, Double ops, Integer inningsOuts, Double era, Integer strikeouts) {

        public Totals {
            teamIds = List.copyOf(teamIds);
        }
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

    /** 그 시즌 뛴 MLB 구단 번호(뛴 순서). 모르면 빈 목록 */
    public List<Long> getTeamIds() {
        if (teamIds == null || teamIds.isBlank()) {
            return List.of();
        }
        return Arrays.stream(teamIds.split(",")).map(String::trim).map(Long::valueOf).toList();
    }

    public Integer getGames() {
        return games;
    }

    public Integer getPlateAppearances() {
        return plateAppearances;
    }

    public Double getBattingAvg() {
        return battingAvg;
    }

    public Integer getHomeRuns() {
        return homeRuns;
    }

    public Double getOps() {
        return ops;
    }

    public Integer getInningsOuts() {
        return inningsOuts;
    }

    public Double getEra() {
        return era;
    }

    public Integer getStrikeouts() {
        return strikeouts;
    }

    public Double getWrcPlus() {
        return wrcPlus;
    }

    public Double getEraMinus() {
        return eraMinus;
    }

    public Double getWar() {
        return war;
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }

    /** 지난 시즌 지표를 선수별로 물은 때. null이면 아직 묻지 않았다(지금 시즌 줄은 늘 null) */
    public Instant getMetricsFetchedAt() {
        return metricsFetchedAt;
    }
}
