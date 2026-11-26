package com.korleaguer.api.season;

import org.jspecify.annotations.Nullable;

/**
 * 기간 타격 합계. avg는 타수가 0이면 null, seasonAvg는 선수 페이지와 같은 정규시즌 타율이다.
 * wrcPlus는 기간 wRC+(plusLevel 단계의 그 시즌 리그 평균 = 100, 0~300, 구장 보정 없음). 메이저는 plusLevel이 MLB,
 * 마이너·재활은 그 단계 약어(트리플A면 AAA)다. 기간이 한 단계가 아니거나 그 단계 평균을 모르거나, 타석이 없거나
 * 경기별 세부 칸이 모자라면 null이다(plusLevel도 null).
 * mixedLevels는 기간 경기가 둘 이상의 마이너 단계에 걸쳐 있어 한 평균과 견주지 못했다는 뜻이다(재활 중 루키 → 더블A →
 * 트리플A처럼. 이때 wrcPlus는 늘 null). 한 단계거나 메이저면 false다. 웹이 빈 판정 줄 대신 사실을 적는 데 쓴다(이슈 #218).
 */
public record PeriodHitting(
        int games,
        int atBats,
        int hits,
        int homeRuns,
        int rbi,
        int walks,
        int stolenBases,
        @Nullable Double avg,
        @Nullable Double seasonAvg,
        @Nullable Double wrcPlus,
        @Nullable String plusLevel,
        boolean mixedLevels) {
}
