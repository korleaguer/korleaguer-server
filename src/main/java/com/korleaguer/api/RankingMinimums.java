package com.korleaguer.api;

import com.korleaguer.support.BaseballMath;

/**
 * 시즌 순위(타율·OPS·평균자책점)에 넣는 최소 표본. 몇 타석만 뛰고 .500을 친 선수가 1위가 되지 않게 거른다.
 * 규정타석(팀 경기 수 × 3.1)·규정이닝(× 1)은 주전이 적은 해외파에게 너무 빡빡해서(2026년 타자는 이정후 한 명만 채움)
 * 표본이 아주 적은 선수만 거르는 느슨한 기준을 쓴다.
 * <ul>
 *   <li>타자: 타석이 팀 경기 수 × 0.5 이상(최소 10타석). 162경기면 81타석.</li>
 *   <li>투수: 아웃이 팀 경기 수 × 0.6(= 0.2이닝) 이상(최소 15아웃 = 5이닝). 162경기면 98아웃(32⅔이닝).</li>
 * </ul>
 * 홈런·타점·탈삼진처럼 쌓이는 기록은 이 기준 없이 비교한다(화면이 정한다).
 *
 * @param teamGames 시즌이 얼마나 지났는지: 우리가 받는 메이저 구단 중 이번 정규시즌에 끝난 경기가 가장 많은 구단의 경기 수
 */
public record RankingMinimums(int teamGames, int minPlateAppearances, int minOuts, String minInningsText) {

    private static final int MIN_PLATE_APPEARANCES = 10;
    private static final int MIN_OUTS = 15;

    static RankingMinimums of(int teamGames) {
        // 소수 계산 오차(10 × 0.6 = 6.000…01)로 올림이 하나 커지지 않게 정수로 올림한다.
        int plateAppearances = Math.max(MIN_PLATE_APPEARANCES, ceilDiv(teamGames, 2));      // × 0.5
        int outs = Math.max(MIN_OUTS, ceilDiv(teamGames * 3, 5));                            // × 0.6
        return new RankingMinimums(teamGames, plateAppearances, outs, BaseballMath.inningsText(outs));
    }

    private static int ceilDiv(int value, int divisor) {
        return Math.ceilDiv(Math.max(0, value), divisor);
    }

    boolean qualifies(HitterSeason hitter) {
        return hitter.plateAppearances() >= minPlateAppearances;
    }

    boolean qualifies(PitcherSeason pitcher) {
        return pitcher.outs() >= minOuts;
    }
}
