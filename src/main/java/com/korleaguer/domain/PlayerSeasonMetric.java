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
 * 선수의 한 시즌 메이저 합계(타석·이닝·선발)와 고급 지표(wRC+·ERA-·WAR). (선수, 시즌, 묶음)마다 한 줄이다.
 * 비시즌 홈의 시즌 성적표 판정에 쓴다. 수집기(SeasonMetricRefresher)가 끝난 시즌과 그 앞 3시즌을 받아 덮어쓴다.
 * 그 시즌 메이저 기록이 없으면 합계는 0이고 지표는 null이다. 지표를 받지 못했어도 null이다.
 */
@Entity
public class PlayerSeasonMetric {

    /** 기록 묶음. 타자는 타격, 투수는 투구 */
    public enum Group {
        HITTING,
        PITCHING;

        public static Group of(Role role) {
            return role == Role.PITCHER ? PITCHING : HITTING;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "player_id")
    private Player player;

    private int season;

    @Enumerated(EnumType.STRING)
    private Group statGroup;

    private Integer plateAppearances;
    private Integer inningsOuts;
    private Integer gamesStarted;
    private Double wrcPlus;
    private Double eraMinus;
    private Double war;
    private Instant fetchedAt;

    protected PlayerSeasonMetric() {
    }

    public PlayerSeasonMetric(Player player, int season, Group group) {
        this.player = player;
        this.season = season;
        this.statGroup = group;
    }

    public void updateTotals(Integer plateAppearances, Integer inningsOuts, Integer gamesStarted, Instant fetchedAt) {
        this.plateAppearances = plateAppearances;
        this.inningsOuts = inningsOuts;
        this.gamesStarted = gamesStarted;
        this.fetchedAt = fetchedAt;
    }

    public void updateMetrics(Double wrcPlus, Double eraMinus, Double war) {
        this.wrcPlus = wrcPlus;
        this.eraMinus = eraMinus;
        this.war = war;
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

    public Group getStatGroup() {
        return statGroup;
    }

    /** 메이저 타석. 투수면 null */
    public Integer getPlateAppearances() {
        return plateAppearances;
    }

    /** 메이저 이닝(아웃 수). 타자면 null */
    public Integer getInningsOuts() {
        return inningsOuts;
    }

    /** 메이저 선발 등판 수. 타자면 null */
    public Integer getGamesStarted() {
        return gamesStarted;
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
}
