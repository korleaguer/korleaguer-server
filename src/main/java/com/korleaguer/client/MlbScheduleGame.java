package com.korleaguer.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 일정의 경기 한 개(schedule의 dates[].games[]).
 * gameDate는 시작 시각(UTC, 예: 2026-09-26T20:05:00Z), officialDate는 공식 날짜(미국 날짜)다.
 * gameType: R 정규시즌, F·D·L·W 포스트시즌, S 시범경기, E 친선경기, A 올스타.
 * 연기된 경기는 원래 날짜와 다시 잡힌 날짜에 같은 gamePk로 두 번 나올 수 있다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MlbScheduleGame(
        Long gamePk,
        String gameType,
        String season,
        String gameDate,
        String officialDate,
        Status status,
        Teams teams,
        Venue venue,
        String doubleHeader,
        Integer gameNumber) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Status(String abstractGameState, String codedGameState, String detailedState) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Teams(Side away, Side home) {
    }

    /** score는 경기 전이면 없다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Side(MlbTeam team, Integer score) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Venue(String name) {
    }
}
