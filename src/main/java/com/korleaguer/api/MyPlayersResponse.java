package com.korleaguer.api;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * GET /api/my(내 선수). 기간은 선수마다 한국 날짜 [fromKst, toKst]의 days일이다(MyPlayer).
 * 맨 위 fromKst·toKst는 오늘까지 days일이고, isFallback은 한 명이라도 기간을 당겼으면 true다.
 * players는 요청한 순서이고, 없는 id는 빠진다.
 */
public record MyPlayersResponse(
        String dataMode,
        boolean statsAvailable,
        int season,
        Instant generatedAt,
        int days,
        LocalDate fromKst,
        LocalDate toKst,
        // 이름이 is로 시작하는 boolean은 JSON 이름이 바뀔 수 있어 직접 적어 둔다.
        @JsonProperty("isFallback") boolean isFallback,
        List<MyPlayer> players) {

    /**
     * 선수 한 명. 타자면 pitching이, 투수면 hitting이 null이다.
     * 선수 기록을 받지 못한 상태(statsAvailable=false)면 hitting·pitching·streak 모두 null이다.
     * streak은 이번 시즌(정규시즌+포스트시즌) 최근 경기부터 이어진 연속 기록이다(타자: 안타 경기, 투수: 무실점 등판).
     * games는 기간 안 경기 한 줄 기록(결장·경기 전 포함), 최신순이다.
     * 기간: 보통은 오늘까지 days일(isFallback=false). 최근 days일에 구단 경기(메이저에 있을 때)도 본인 출전도 없으면
     * (구단 시즌이 끝났거나 마이너에 있음) 그 선수가 마지막으로 뛴 날(toKst)까지 days일이다(isFallback=true).
     */
    public record MyPlayer(
            PlayerSummary player,
            LocalDate fromKst,
            LocalDate toKst,
            @JsonProperty("isFallback") boolean isFallback,
            PeriodHitting hitting,
            PeriodPitching pitching,
            Integer streak,
            List<GameLine> games) {
    }

    /** 기간 타격 합계. avg는 타수가 0이면 null, seasonAvg는 선수 페이지와 같은 정규시즌 타율이다. */
    public record PeriodHitting(
            int games,
            int atBats,
            int hits,
            int homeRuns,
            int rbi,
            int walks,
            int stolenBases,
            Double avg,
            Double seasonAvg) {
    }

    /**
     * 기간 투구 합계. outs·earnedRuns·era는 아웃 수를 아는 경기만 센다(아웃이 0이면 era는 null).
     * seasonEra는 선수 페이지와 같은 정규시즌 평균자책점이다.
     */
    public record PeriodPitching(
            int games,
            int outs,
            String inningsText,
            int earnedRuns,
            Double era,
            int strikeouts,
            int wins,
            int losses,
            int saves,
            Double seasonEra) {
    }
}
