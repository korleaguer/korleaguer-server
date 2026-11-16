package com.korleaguer.api;

/**
 * wOBA·wRC+ 계산(이슈 #175). 가중치와 척도는 FanGraphs 표준값 근사로, 웹 선수 화면 '타격 흐름'(src/lib/wrcTrend.ts)과 같다.
 * 해마다 조금씩 다르지만 리그 wOBA도 같은 가중치로 내므로(LeagueEnvironments) '리그 평균 = 100'은 맞는다.
 * 메이저는 선수의 MLB 공식 시즌 wRC+(구장 보정 포함)에 맞춰 옮겨 쓴다(SeasonMonths).
 */
final class WrcMath {

    static final double BB = 0.689;
    static final double HBP = 0.72;
    static final double SINGLE = 0.882;
    static final double DOUBLE = 1.254;
    static final double TRIPLE = 1.59;
    static final double HOME_RUN = 2.05;
    static final double SCALE = 1.242;

    private WrcMath() {
    }

    /** wOBA 분자·분모를 더해 가는 그릇 */
    static final class Sum {
        private double numerator;
        private int denominator;

        void add(int atBats, int hits, int doubles, int triples, int homeRuns, int walks, int hitByPitch, int sacFlies) {
            int singles = hits - doubles - triples - homeRuns;
            numerator += BB * walks + HBP * hitByPitch + SINGLE * singles + DOUBLE * doubles + TRIPLE * triples
                    + HOME_RUN * homeRuns;
            denominator += atBats + walks + sacFlies + hitByPitch;
        }

        /** 타석(분모)이 없으면 null */
        Double woba() {
            return denominator == 0 ? null : numerator / denominator;
        }
    }

    /** 그 리그 평균 대비 wRC+(100 = 평균, 구장 보정 없음) */
    static double wrcPlus(double woba, LeagueEnvironments.Env league) {
        return ((woba - league.woba()) / SCALE + league.runsPerPa()) / league.runsPerPa() * 100;
    }
}
