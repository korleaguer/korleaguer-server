package com.korleaguer.api.screen;

import com.korleaguer.api.game.GameLine;
import com.korleaguer.api.records.GameBuzz;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.util.List;

/**
 * 최근 경기.
 * isFallback=false: 최근 24시간 경기, true: 24시간 안에 경기가 없어 마지막 경기일(dateKst)의 경기
 * buzz는 줄마다 화제 문구(없는 줄은 빠진다, 선수 id·경기 id로 줄과 짝지음, 이슈 #207)
 */
public record RecentGames(
        // 이름이 is로 시작하는 boolean은 JSON 이름이 바뀔 수 있어 직접 적어 둔다.
        @JsonProperty("isFallback") boolean isFallback,
        @Nullable LocalDate dateKst,
        List<GameLine> lines,
        List<GameBuzz> buzz) {
}
