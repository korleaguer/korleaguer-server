package com.korleaguer.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class Team {

    // balldontlie 팀 id를 그대로 기본키로 쓴다.
    @Id
    private Long bdlTeamId;

    private String abbreviation;
    private String nameEn;
    private String location;
    private String league;

    protected Team() {
    }

    public Team(Long bdlTeamId) {
        this.bdlTeamId = bdlTeamId;
    }

    public void update(String abbreviation, String nameEn, String location, String league) {
        this.abbreviation = abbreviation;
        this.nameEn = nameEn;
        this.location = location;
        this.league = league;
    }

    public Long getBdlTeamId() {
        return bdlTeamId;
    }

    public String getAbbreviation() {
        return abbreviation;
    }

    public String getNameEn() {
        return nameEn;
    }

    public String getLocation() {
        return location;
    }

    public String getLeague() {
        return league;
    }
}
