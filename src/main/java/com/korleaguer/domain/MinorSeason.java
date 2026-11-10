package com.korleaguer.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

import java.time.Instant;

/** 선수의 한 시즌 마이너리그(트리플A ~ 루키) 출전 경기 수. 선수 정보와 같이 하루 한 번 새로 받아 덮어쓴다. */
@Entity
public class MinorSeason {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "player_id")
    private Player player;

    private int season;
    private int hittingGames;
    private int pitchingGames;
    private Instant fetchedAt;

    protected MinorSeason() {
    }

    public MinorSeason(Player player, int season) {
        this.player = player;
        this.season = season;
    }

    public void update(int hittingGames, int pitchingGames, Instant fetchedAt) {
        this.hittingGames = hittingGames;
        this.pitchingGames = pitchingGames;
        this.fetchedAt = fetchedAt;
    }

    public Player getPlayer() {
        return player;
    }

    public int getSeason() {
        return season;
    }

    public int getHittingGames() {
        return hittingGames;
    }

    public int getPitchingGames() {
        return pitchingGames;
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }
}
