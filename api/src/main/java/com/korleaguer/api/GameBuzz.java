package com.korleaguer.api;

/**
 * 홈 경기 카드 안 화제 문구 한 줄(2026-11-21 사용자 결정). 선수 × 경기마다 최대 하나다.
 * kind는 PASSED(한국인 통산 기록 넘음)·FIRST(메이저 데뷔·올 시즌 처음)·STREAK(연속 안타)·SEASON_BEST(올 시즌 최고 경기)이고,
 * 우선순위도 이 순서다. text는 맥락(처음·넘음·연속)만 말하고 그 경기 한 줄 기록의 숫자는 되풀이하지 않는다.
 * stat은 문구가 이미 말하는 활약(홈런 homeRuns, 안타 hits, 도루 stolenBases, 타점 rbi, 승 wins, 세이브 saves, 탈삼진
 * strikeouts)으로, 웹이 같은 뜻의 활약 배지를 카드에서 뺄 때 쓴다. 활약을 말하지 않는 문구는 null이다.
 */
public record GameBuzz(long playerId, long gameId, String kind, String stat, String text) {

    public static final String PASSED = "PASSED";
    public static final String FIRST = "FIRST";
    public static final String STREAK = "STREAK";
    public static final String SEASON_BEST = "SEASON_BEST";
}
