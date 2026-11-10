package com.korleaguer.domain;

import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

/** 선수 한 명의 경기 한 번 기록. (player, game)마다 한 줄이다. */
@Entity
public class Appearance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "player_id")
    private Player player;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "game_id")
    private Game game;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id")
    private Team team;

    @Embedded
    private Batting batting;

    @Embedded
    private Pitching pitching;

    // 선발·교체. 박스스코어로 정하며 모르면 null이다.
    @Enumerated(EnumType.STRING)
    private Lineup lineup;

    protected Appearance() {
    }

    public Appearance(Player player, Game game) {
        this.player = player;
        this.game = game;
    }

    public void update(Team team, Batting batting, Pitching pitching) {
        this.team = team;
        this.batting = batting;
        this.pitching = pitching;
    }

    public void updateLineup(Lineup lineup) {
        this.lineup = lineup;
    }

    public Long getId() {
        return id;
    }

    public Player getPlayer() {
        return player;
    }

    public Game getGame() {
        return game;
    }

    public Team getTeam() {
        return team;
    }

    /** 타격 기록이 하나도 없으면 null */
    public Batting getBatting() {
        return batting;
    }

    /** 투구 기록이 하나도 없으면 null */
    public Pitching getPitching() {
        return pitching;
    }

    /** 선발(STARTER)·교체(SUB). 모르면 null */
    public Lineup getLineup() {
        return lineup;
    }
}
