package com.korleaguer.api.season;

/**
 * wRC+ 계산(이슈 #175). 척도는 FanGraphs 표준값 근사로, 웹 선수 화면 '타격 흐름'(src/lib/wrcTrend.ts)과 같다.
 * wOBA 가중치와 더하기는 수집(리그 평균)과 같이 써서 core에 있다(support.Woba).
 * 메이저는 선수의 MLB 공식 시즌 wRC+(구장 보정 포함)에 맞춰 옮겨 쓴다(SeasonMonths).
 */
final class WrcMath {

    static final double SCALE = 1.242;

    private WrcMath() {
    }

    /** 그 리그 평균 대비 wRC+(100 = 평균, 구장 보정 없음) */
    static double wrcPlus(double woba, LeagueEnvironments.Env league) {
        return ((woba - league.woba()) / SCALE + league.runsPerPa()) / league.runsPerPa() * 100;
    }
}
