package com.korleaguer.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

import java.time.LocalDate;

/**
 * 선수의 소속. MVP는 이력을 쌓지 않고 선수마다 한 줄만 고쳐 쓴다.
 * endDate가 null이면 지금 소속이고, 소속이 끝났으면(FA 등) 끝난 날이 들어 있는 마지막 소속이다(줄은 지우지 않는다).
 */
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

    /** 지금 소속 팀으로 고친다. 소속이 끝났던 줄이면(FA였다가 계약) 다시 지금 소속이 된다. */
    public void changeTeam(Team team) {
        this.team = team;
        this.endDate = null;
    }

    /** 소속이 끝났다(FA 등). 이미 끝났으면 처음 끝난 날을 그대로 둔다. */
    public void end(LocalDate date) {
        if (endDate == null) {
            endDate = date;
        }
    }

    /** 소속이 끝났는지(FA 등). 끝났으면 team은 마지막 소속이다. */
    public boolean isEnded() {
        return endDate != null;
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
