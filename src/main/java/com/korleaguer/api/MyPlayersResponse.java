package com.korleaguer.api;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * GET /api/my(내 선수). 기간은 선수마다 한국 날짜 [fromKst, toKst]의 days일이다(MyPlayer).
 * 맨 위 fromKst·toKst는 오늘까지 days일이고, isFallback은 마지막으로 뛴 날까지 당긴 기간을 보이는 카드(basis LAST_PLAYED,
 * 또는 마지막 재활 경기까지 당긴 REHAB)가 한 장이라도 있으면 true다.
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
            List<GameLine> games,
            CardStatus status,
            Play play,
            Fact fact,
            Basis basis,
            String level,
            SeasonTotals seasonTotals) {
    }

    /**
     * 카드의 큰 숫자·막대(hitting·pitching·games)가 어떤 기간인지. level은 그 기록의 단계다(MLB 또는 마이너 약어 AAA·AA 등).
     * RECENT: 오늘까지 days일, LAST_PLAYED: 마지막으로 뛴 날까지 days일, BEFORE_INJURY: 다치기 전(메이저 부상자 명단에 오르기 전) days일,
     * REHAB: 메이저 부상자 명단에 오른 뒤 뛴 마이너 재활 경기(오늘까지, 없으면 마지막 재활 경기까지 days일. isFallback이 그 구분이고
     * 시즌 비교 값은 없다), SEASON: 비시즌·FA라 기간 대신 시즌 합계(seasonTotals), NONE: 보여 줄 기록이 없음(이번 시즌 경기가 아직 없음 등)
     */
    public enum Basis {
        RECENT,
        LAST_PLAYED,
        BEFORE_INJURY,
        REHAB,
        SEASON,
        NONE
    }

    /**
     * 상태 배지. 메이저에서 평소대로 뛰고 있으면 status 자체가 null이다.
     * kind: IL(부상자 명단) · RETURNED(부상자 명단에서 복귀, 7일 안) · CALLED_UP(콜업, 7일 안) · MINORS(마이너) ·
     * MINORS_IL(마이너 부상자 명단) · SUSPENDED(출장 정지) · DFA(지명 할당) · FA · SEASON_OVER(시즌 끝).
     * since는 그 상태가 시작된 미국 날짜(모르면 null), days는 since부터 오늘까지 며칠째인지(since가 없으면 null).
     * level·teamName은 마이너 단계 약어와 마이너 팀 이름(마이너 기록이 있을 때), lastGameDate·lastGameLevel은
     * 마지막 경기의 미국 날짜와 단계(MLB 또는 마이너 약어, FA·시즌 끝일 때만)다.
     */
    public record CardStatus(
            String kind,
            LocalDate since,
            Integer days,
            String level,
            String teamName,
            LocalDate lastGameDate,
            String lastGameLevel) {
    }

    /**
     * 출전 줄. 타자는 recent(지금 상태가 시작된 뒤 팀의 끝난 경기 최근 10개, 오래된 것부터 STARTER·SUB·PLAYED·ABSENT,
     * PLAYED는 선발·교체를 모르는 출전), after는 그 경기들이 복귀·콜업 뒤 경기라 10개가 안 될 때 "RETURNED"·"CALLED_UP"이다.
     * 투수는 appearances14(최근 14일 등판 수)와 daysSinceLast(마지막 등판이 며칠 전인지, 등판이 없으면 null)다.
     * 메이저에 있을 때만 있다.
     */
    public record Play(
            List<String> recent,
            String after,
            Integer appearances14,
            Integer daysSinceLast) {
    }

    /** 한 줄 소식. tone: HOT(좋은 일, 주황) · COLD(안 좋은 일, 파랑). text는 화면에 그대로 쓰는 한국어 문장이다. */
    public record Fact(String tone, String text) {
    }

    /**
     * 시즌 합계(비시즌·FA). primary는 크게 보일 쪽(MLB: 메이저 20경기 이상이거나 메이저가 더 많을 때, 아니면 MINORS).
     * mlb·minors는 그 단계 경기가 없으면 null이다.
     */
    public record SeasonTotals(int season, String primary, Totals mlb, Totals minors) {
    }

    /**
     * 한 단계의 시즌 합계. level은 MLB 또는 마이너 약어(여러 마이너 단계를 뛰었으면 MINORS).
     * 타자는 atBats·hits·homeRuns·avg, 투수는 outs·inningsText·era·strikeouts·saves·holds를 쓴다.
     */
    public record Totals(
            String level,
            int games,
            int atBats,
            int hits,
            int homeRuns,
            Double avg,
            int outs,
            String inningsText,
            Double era,
            int strikeouts,
            int saves,
            int holds) {
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
