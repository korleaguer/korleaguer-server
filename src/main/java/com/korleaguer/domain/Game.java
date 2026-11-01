package com.korleaguer.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

import java.time.Instant;

@Entity
public class Game {

    public static final String STATUS_FINAL = "final";

    @Id
    private Long bdlGameId;

    private int season;
    private String seasonType;
    private Instant startTimeUtc;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "home_team_id")
    private Team homeTeam;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "away_team_id")
    private Team awayTeam;

    private Integer homeScore;
    private Integer awayScore;

    private String statusState;
    private String venue;

    protected Game() {
    }

    public Game(Long bdlGameId) {
        this.bdlGameId = bdlGameId;
    }

    public void update(int season, String seasonType, Instant startTimeUtc, Team homeTeam, Team awayTeam,
                       Integer homeScore, Integer awayScore, String statusState, String venue) {
        this.season = season;
        this.seasonType = seasonType;
        this.startTimeUtc = startTimeUtc;
        this.homeTeam = homeTeam;
        this.awayTeam = awayTeam;
        this.homeScore = homeScore;
        this.awayScore = awayScore;
        this.statusState = statusState;
        this.venue = venue;
    }

    public boolean involves(Team team) {
        return homeTeam.getBdlTeamId().equals(team.getBdlTeamId())
                || awayTeam.getBdlTeamId().equals(team.getBdlTeamId());
    }

    public boolean isHome(Team team) {
        return homeTeam.getBdlTeamId().equals(team.getBdlTeamId());
    }

    public Long getBdlGameId() {
        return bdlGameId;
    }

    public int getSeason() {
        return season;
    }

    public String getSeasonType() {
        return seasonType;
    }

    public Instant getStartTimeUtc() {
        return startTimeUtc;
    }

    public Team getHomeTeam() {
        return homeTeam;
    }

    public Team getAwayTeam() {
        return awayTeam;
    }

    public Integer getHomeScore() {
        return homeScore;
    }

    public Integer getAwayScore() {
        return awayScore;
    }

    public String getStatusState() {
        return statusState;
    }

    public String getVenue() {
        return venue;
    }
}
