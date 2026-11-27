package com.korleaguer.api.card;

import com.korleaguer.api.season.LevelTotals;
import com.korleaguer.api.season.PeriodHitting;
import com.korleaguer.api.season.PeriodPitching;

import com.korleaguer.api.game.GameLine;
import com.korleaguer.api.player.PlayerSummary;
import com.korleaguer.api.records.GameBuzz;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * GET /api/my(내 선수). 기간은 선수마다 한국 날짜 [fromKst, toKst]의 days일이다(MyPlayer).
 * 맨 위 fromKst·toKst는 오늘까지 days일이고, isFallback은 마지막으로 뛴 날까지 당긴 기간을 보이는 카드(basis LAST_PLAYED,
 * 또는 마지막 재활 경기까지 당긴 REHAB)가 한 장이라도 있으면 true다.
 * players는 요청한 순서이고, 없는 id는 빠진다.
 * buzz는 players 줄(games) 중 화제 문구가 붙은 메이저 정규시즌 경기들이다(GameBuzz, 홈 recent.buzz와 같은 규칙, 이슈 #209).
 * 선수 id·경기 id로 줄과 짝지으며 없는 줄은 빠진다. 마이너·포스트시즌 줄에는 없다.
 * statsAvailable은 늘 true다(MLB 공식 API는 경기별 선수 기록을 늘 준다). 웹 호환 때문에 남겨 둔다.
 */
public record MyPlayersResponse(
        @Schema(allowableValues = {"sample", "mlb"}) String dataMode,
        boolean statsAvailable,
        int season,
        Instant generatedAt,
        int days,
        LocalDate fromKst,
        LocalDate toKst,
        // 이름이 is로 시작하는 boolean은 JSON 이름이 바뀔 수 있어 직접 적어 둔다.
        @JsonProperty("isFallback") boolean isFallback,
        List<MyPlayer> players,
        List<GameBuzz> buzz) {

    public MyPlayersResponse {
        // 응답 계약: buzz는 늘 배열이다(없으면 빈 배열)
        buzz = buzz == null ? List.of() : buzz;
    }

    /**
     * 선수 한 명. 타자면 pitching이, 투수면 hitting이 null이다.
     * streak은 이번 시즌(정규시즌+포스트시즌) 최근 끝난 경기부터 이어진 연속 기록이다(타자: 안타 경기, 투수: 무실점 등판).
     * 한 줄 소식과 같은 경기로 센다: 진행 중 경기는 넣지 않고, 마이너 카드는 마이너 경기로 센다.
     * games는 기간 안 경기 한 줄 기록(결장·경기 전 포함), 최신순이다. 마이너 경기 줄의 startTimeKst는 선수 화면 마이너
     * 경기 줄(minorGames)과 같은 시각이다(같은 경기가 같은 날, 이슈 #183).
     * 기간: 보통은 오늘까지 days일(isFallback=false). 최근 days일에 구단 경기(메이저에 있을 때)도 본인 출전도 없으면
     * (구단 시즌이 끝났거나 마이너에 있음) 그 선수가 마지막으로 뛴 날(toKst)까지 days일이다(isFallback=true).
     */
    public record MyPlayer(
            PlayerSummary player,
            LocalDate fromKst,
            LocalDate toKst,
            @JsonProperty("isFallback") boolean isFallback,
            @Nullable PeriodHitting hitting,
            @Nullable PeriodPitching pitching,
            @Nullable Integer streak,
            List<GameLine> games,
            @Nullable CardStatus status,
            @Nullable CardPlay play,
            @Nullable CardFact fact,
            Basis basis,
            @Nullable String level,
            @Nullable SeasonTotals seasonTotals) {
    }

    /**
     * 카드의 큰 숫자·막대(hitting·pitching·games)가 어떤 기간인지. level은 그 기록의 단계다(MLB 또는 마이너 약어 AAA·AA 등).
     * RECENT: 오늘까지 days일, LAST_PLAYED: 마지막으로 뛴 날까지 days일, BEFORE_INJURY: 다치기 전(메이저 부상자 명단에 오르기 전) days일,
     * REHAB: 메이저 부상자 명단에 오른 뒤 뛴 마이너 재활 경기(오늘까지, 없으면 마지막 재활 경기까지 days일. isFallback이 그 구분이고
     * 시즌 비교 값은 없다), SEASON: FA·시즌 끝·국내 리그 복귀(KBO)라 기간 대신 시즌 합계(seasonTotals), NONE: 보여 줄 기록이 없음(이번 시즌 경기가 아직 없음 등)
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
     * 시즌 합계(FA·시즌 끝·KBO). primary는 크게 보일 쪽(MLB: 메이저 20경기 이상이거나 메이저가 더 많을 때, 아니면 MINORS).
     * mlb·minors는 그 단계 경기가 없으면 null이다.
     */
    public record SeasonTotals(int season, @Schema(allowableValues = {"MLB", "MINORS"}) @Nullable String primary,
                                @Nullable LevelTotals mlb, @Nullable LevelTotals minors) {
    }

}
