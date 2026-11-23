package com.korleaguer.api.season;

/**
 * 기간 투구 합계. outs·earnedRuns·era는 아웃 수를 아는 경기만 센다(아웃이 0이면 era는 null).
 * seasonEra는 선수 페이지와 같은 정규시즌 평균자책점이다.
 * eraPlus는 기간 ERA+(plusLevel 단계의 그 시즌 리그 평균 = 100, 높을수록 잘함, 300까지, 무자책이면 300). plusLevel은
 * PeriodHitting과 같다. 아웃을 아는 경기가 없거나 단계가 한 단계가 아니면 null이다. mixedLevels도 PeriodHitting과 같다.
 */
public record PeriodPitching(
        int games,
        int outs,
        String inningsText,
        int earnedRuns,
        Double era,
        int strikeouts,
        int wins,
        int losses,
        int saves,
        Double seasonEra,
        Double eraPlus,
        String plusLevel,
        boolean mixedLevels) {
}
