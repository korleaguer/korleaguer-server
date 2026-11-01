package com.korleaguer.api;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.util.List;

/**
 * 최근 경기.
 * isFallback=false: 최근 24시간 경기, true: 24시간 안에 경기가 없어 마지막 경기일(dateKst)의 경기
 */
public record RecentGames(
        // 이름이 is로 시작하는 boolean은 JSON 이름이 바뀔 수 있어 직접 적어 둔다.
        @JsonProperty("isFallback") boolean isFallback,
        LocalDate dateKst,
        List<GameLine> lines) {
}
