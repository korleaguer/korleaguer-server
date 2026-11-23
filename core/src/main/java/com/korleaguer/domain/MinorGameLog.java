package com.korleaguer.domain;

import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

import java.time.LocalDate;

/**
 * 선수의 마이너리그 경기 한 번 기록. (player, season, gamePk)마다 한 줄이고, 같은 경기의 타격·투구를 함께 둔다.
 * 마이너 경기는 game 표(메이저 일정)에 없으므로 날짜·팀 이름을 이 줄에 직접 둔다.
 * gameDate는 공식 날짜(미국 날짜), level은 리그 단계 약어(AAA·AA·A+·A·ROK)다.
 * teamId·opponentId는 MLB 팀 번호(그 경기 선수 팀·상대 팀), schedule은 MLB 일정에서 받은 시작 시각·점수다(아직 모르면 null).
 */
@Entity
public class MinorGameLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "player_id")
    private Player player;

    private int season;
    private long gamePk;
    private LocalDate gameDate;
    private String level;
    private String teamName;
    private String opponentName;
    private Long teamId;
    private Long opponentId;
    private Boolean home;
    private Boolean win;

    @Embedded
    private MinorGameSchedule schedule;

    @Embedded
    private Batting batting;

    @Embedded
    private Pitching pitching;

    private Integer holds;
    private Integer blownSaves;

    protected MinorGameLog() {
    }

    public MinorGameLog(Player player, int season, long gamePk) {
        this.player = player;
        this.season = season;
        this.gamePk = gamePk;
    }

    public void updateGame(LocalDate gameDate, String level, String teamName, String opponentName, Boolean home,
                           Boolean win) {
        this.gameDate = gameDate;
        this.level = level;
        this.teamName = teamName;
        this.opponentName = opponentName;
        this.home = home;
        this.win = win;
    }

    public void updateTeamIds(Long teamId, Long opponentId) {
        this.teamId = teamId;
        this.opponentId = opponentId;
    }

    public void updateSchedule(MinorGameSchedule schedule) {
        this.schedule = schedule;
    }

    public void updateBatting(Batting batting) {
        this.batting = batting;
    }

    public void updatePitching(Pitching pitching, Integer holds, Integer blownSaves) {
        this.pitching = pitching;
        this.holds = holds;
        this.blownSaves = blownSaves;
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

    public long getGamePk() {
        return gamePk;
    }

    public LocalDate getGameDate() {
        return gameDate;
    }

    public String getLevel() {
        return level;
    }

    public String getTeamName() {
        return teamName;
    }

    public String getOpponentName() {
        return opponentName;
    }

    public Long getTeamId() {
        return teamId;
    }

    public Long getOpponentId() {
        return opponentId;
    }

    /** 일정에서 받은 시작 시각·점수. 아직 묻지 않았거나 받지 못했으면 null */
    public MinorGameSchedule getSchedule() {
        return schedule;
    }

    public Boolean getHome() {
        return home;
    }

    public Boolean getWin() {
        return win;
    }

    /** 타격 기록이 없으면(등판만 한 경기) null */
    public Batting getBatting() {
        return batting;
    }

    /** 투구 기록이 없으면(타자로만 나온 경기) null */
    public Pitching getPitching() {
        return pitching;
    }

    public Integer getHolds() {
        return holds;
    }

    public Integer getBlownSaves() {
        return blownSaves;
    }
}
