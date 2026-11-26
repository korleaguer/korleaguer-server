package com.korleaguer.api.season;

/**
 * ERA+ 계산(100 = 리그 평균, 높을수록 잘함). 달 막대(SeasonMonths)·시즌 숫자(SeasonRates)·내 선수 카드 기간(PeriodRates)이
 * 같은 식을 쓴다. 상한(SeasonMonths.ERA_PLUS_CAP)·반올림 자리·표본 기준과 무자책(자책점 0)·아웃 없음 처리는 쓰는 쪽이 정한다.
 */
final class EraMath {

    private EraMath() {
    }

    /** 그 리그 평균 대비 ERA+ = 100 × 리그 평균자책점 ÷ 평균자책점. 자책점과 아웃은 0보다 커야 한다 */
    static double eraPlus(int earnedRuns, int outs, LeagueEnv league) {
        return 100 * league.era() / (earnedRuns * 27.0 / outs);
    }

    /** 공식 ERA-(낮을수록 잘함)를 뒤집은 ERA+ = 10000 ÷ ERA-. ERA-는 0보다 커야 한다 */
    static double fromMinus(double eraMinus) {
        return 10000 / eraMinus;
    }
}
