package com.korleaguer.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

import java.time.Instant;

/** 선수의 한 시즌 기록(gameLog)을 한 번 다 받았다는 표시. 있으면 그 시즌은 다시 백필하지 않는다. */
@Entity
public class PlayerSeason {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "player_id")
    private Player player;

    private int season;
    private Instant fetchedAt;

    protected PlayerSeason() {
    }

    public PlayerSeason(Player player, int season, Instant fetchedAt) {
        this.player = player;
        this.season = season;
        this.fetchedAt = fetchedAt;
    }

    public void refetched(Instant fetchedAt) {
        this.fetchedAt = fetchedAt;
    }

    public int getSeason() {
        return season;
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }
}
