package com.korleaguer.support;

/**
 * wOBA 가중치와 더하기(이슈 #175). 가중치는 FanGraphs 표준값 근사로, 웹 선수 화면 '타격 흐름'(src/lib/wrcTrend.ts)과 같다.
 * 해마다 조금씩 다르지만 선수 wOBA(조회: api.PeriodRates·SeasonMonths)와 리그 wOBA(수집: ingest.LeagueAverageRefresher)를
 * 같은 가중치로 내므로 '리그 평균 = 100'은 맞는다. 두 쪽이 꼭 같은 가중치를 써야 해서 여기(core)에 둔다(이슈 #233).
 * wRC+로 바꾸는 계산과 판정 규칙은 조회 쪽(api.WrcMath)에 있다.
 */
public final class Woba {

    public static final double BB = 0.689;
    public static final double HBP = 0.72;
    public static final double SINGLE = 0.882;
    public static final double DOUBLE = 1.254;
    public static final double TRIPLE = 1.59;
    public static final double HOME_RUN = 2.05;

    private Woba() {
    }

    /** wOBA 분자·분모를 더해 가는 그릇 */
    public static final class Sum {
        private double numerator;
        private int denominator;

        public void add(int atBats, int hits, int doubles, int triples, int homeRuns, int walks, int hitByPitch,
                        int sacFlies) {
            int singles = hits - doubles - triples - homeRuns;
            numerator += BB * walks + HBP * hitByPitch + SINGLE * singles + DOUBLE * doubles + TRIPLE * triples
                    + HOME_RUN * homeRuns;
            denominator += atBats + walks + sacFlies + hitByPitch;
        }

        /** 타석(분모)이 없으면 null */
        public Double woba() {
            return denominator == 0 ? null : numerator / denominator;
        }
    }
}
