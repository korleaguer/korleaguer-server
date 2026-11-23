package com.korleaguer.api;

/**
 * 한 경기 투구 숫자. outs(아웃 수)를 모르면 outs·inningsText가 null이다.
 * 나머지 빈 칸(null)은 0(승·패·세이브는 false)으로 둔다.
 */
public record GamePitching(
        Integer outs,
        String inningsText,
        int runs,
        int earnedRuns,
        int strikeouts,
        boolean win,
        boolean loss,
        boolean save) {
}
