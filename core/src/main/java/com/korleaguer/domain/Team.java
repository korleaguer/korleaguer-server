package com.korleaguer.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** MLB 구단. MLB 팀 번호를 그대로 기본키로 쓴다. */
@Entity
public class Team {

    @Id
    private Long mlbTeamId;

    private String abbreviation;
    private String nameEn;

    protected Team() {
    }

    public Team(Long mlbTeamId) {
        this.mlbTeamId = mlbTeamId;
    }

    public void update(String abbreviation, String nameEn) {
        this.abbreviation = abbreviation;
        this.nameEn = nameEn;
    }

    public Long getMlbTeamId() {
        return mlbTeamId;
    }

    public String getAbbreviation() {
        return abbreviation;
    }

    public String getNameEn() {
        return nameEn;
    }
}
