package com.korleaguer.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * 메이저 경기 한 개. MLB gamePk가 기본키다.
 * statusState는 support.GameStatuses가 MLB 상태를 바꾼 값(scheduled, in_progress, final 등)이다.
 * 끝난 경기는 끝난 뒤 SETTLE_AFTER(3일) 동안 하루 한 번 박스스코어로 다시 확인하고(기록 정정 대비),
 * 그 뒤에는 settled로 두어 다시 받지 않는다.
 */
@Entity
public class Game {

    public static final String STATUS_FINAL = "final";
    public static final String STATUS_IN_PROGRESS = "in_progress";
    /** 끝난 경기를 다시 확인하는 기간(기록 정정 대비) */
    public static final Duration SETTLE_AFTER = Duration.ofDays(3);

    @Id
    private Long gamePk;

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

    private Instant finalAt;
    private Instant checkedAt;
    private boolean settled;
    // 선발·교체를 박스스코어로 한 번 채워 봤다(끝난 경기만). 지난 경기를 뒤에서 채울 때 다시 받지 않는다.
    // 그 뒤 선발·교체를 모르는 새 기록이 생기면(시즌 백필) 다시 false가 된다.
    private boolean lineupsChecked;

    protected Game() {
    }

    public Game(Long gamePk) {
        this.gamePk = gamePk;
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

    /** 박스스코어의 점수로 고친다(기록 정정 대비). */
    public void updateScore(Integer homeScore, Integer awayScore) {
        this.homeScore = homeScore;
        this.awayScore = awayScore;
    }

    /** 끝난 것을 처음 봤을 때 시각을 남긴다(그 뒤 박스스코어를 한 번 더 받는 기준). */
    public void markFinalIfNew(Instant now) {
        if (isFinal() && finalAt == null) {
            finalAt = now;
        }
    }

    /**
     * 박스스코어를 받을 때가 됐는지. 진행 중이면 매번, 끝났으면 끝난 뒤 처음 한 번과 그 뒤 하루(UTC 날짜)에 한 번.
     * settled면 받지 않는다.
     */
    public boolean needsBoxscore(Instant now) {
        if (settled) {
            return false;
        }
        if (STATUS_IN_PROGRESS.equals(statusState)) {
            return true;
        }
        if (!isFinal()) {
            return false;
        }
        return checkedAt == null
                || (finalAt != null && checkedAt.isBefore(finalAt))
                || LocalDate.ofInstant(checkedAt, ZoneOffset.UTC).isBefore(LocalDate.ofInstant(now, ZoneOffset.UTC));
    }

    /** 박스스코어를 받았다. 시작한 지 SETTLE_AFTER가 지난 끝난 경기는 settled로 둔다. */
    public void markChecked(Instant now) {
        checkedAt = now;
        settleIfDue(now);
    }

    /** 끝났고 시작한 지 SETTLE_AFTER가 지났으면 settled로 둔다(다시 받지 않는다). */
    public void settleIfDue(Instant now) {
        if (isFinal() && !now.isBefore(startTimeUtc.plus(SETTLE_AFTER))) {
            settled = true;
        }
    }

    /** 끝난 경기의 박스스코어로 선발·교체를 채웠다. */
    public void markLineupsChecked() {
        if (isFinal()) {
            lineupsChecked = true;
        }
    }

    /** 선발·교체를 모르는 기록이 새로 생겼다. 지난 경기를 뒤에서 채울 때 이 경기를 다시 받게 한다. */
    public void markLineupsUnchecked() {
        lineupsChecked = false;
    }

    public boolean isFinal() {
        return STATUS_FINAL.equals(statusState);
    }

    public boolean involves(Team team) {
        return homeTeam.getMlbTeamId().equals(team.getMlbTeamId())
                || awayTeam.getMlbTeamId().equals(team.getMlbTeamId());
    }

    public boolean isHome(Team team) {
        return homeTeam.getMlbTeamId().equals(team.getMlbTeamId());
    }

    public Long getGamePk() {
        return gamePk;
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

    public Instant getFinalAt() {
        return finalAt;
    }

    public Instant getCheckedAt() {
        return checkedAt;
    }

    public boolean isSettled() {
        return settled;
    }

    public boolean isLineupsChecked() {
        return lineupsChecked;
    }
}
