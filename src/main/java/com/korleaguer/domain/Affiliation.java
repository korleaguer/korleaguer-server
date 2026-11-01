package com.korleaguer.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

import java.time.LocalDate;

/** 선수의 소속 이력. endDate가 null이면 현재 소속이다. */
@Entity
public class Affiliation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "player_id")
    private Player player;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id")
    private Team team;

    private LocalDate startDate;
    private LocalDate endDate;
    private boolean loan;

    protected Affiliation() {
    }

    public Affiliation(Player player, Team team, LocalDate startDate) {
        this.player = player;
        this.team = team;
        this.startDate = startDate;
    }

    // MVP는 이력을 쌓지 않고 현재 팀 한 줄만 고쳐 쓴다.
    public void changeTeam(Team team) {
        this.team = team;
    }

    public Long getId() {
        return id;
    }

    public Player getPlayer() {
        return player;
    }

    public Team getTeam() {
        return team;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public boolean isLoan() {
        return loan;
    }
}
