package com.korleaguer.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.time.Instant;
import java.time.LocalDate;

/**
 * MLB 시즌 날짜(미국 날짜). 시즌 연도를 그대로 기본키로 쓴다.
 * 수집기가 하루 한 번 올해·내년 것을 받아 덮어쓰고, 지금 시즌을 정할 때(CurrentSeason) 정규시즌 개막일을 본다.
 */
@Entity
public class SeasonDates {

    @Id
    private Integer season;

    private LocalDate regularSeasonStart;
    private LocalDate regularSeasonEnd;
    private LocalDate postSeasonEnd;
    private Instant fetchedAt;

    protected SeasonDates() {
    }

    public SeasonDates(int season) {
        this.season = season;
    }

    public void update(LocalDate regularSeasonStart, LocalDate regularSeasonEnd, LocalDate postSeasonEnd,
                       Instant fetchedAt) {
        this.regularSeasonStart = regularSeasonStart;
        this.regularSeasonEnd = regularSeasonEnd;
        this.postSeasonEnd = postSeasonEnd;
        this.fetchedAt = fetchedAt;
    }

    public int getSeason() {
        return season;
    }

    public LocalDate getRegularSeasonStart() {
        return regularSeasonStart;
    }

    public LocalDate getRegularSeasonEnd() {
        return regularSeasonEnd;
    }

    /** 포스트시즌 마지막 날. MLB가 주지 않았으면 null */
    public LocalDate getPostSeasonEnd() {
        return postSeasonEnd;
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }
}
