package com.korleaguer.api.season;

import org.jspecify.annotations.Nullable;

/**
 * 한 단계의 시즌 합계. level은 MLB 또는 마이너 약어(여러 마이너 단계를 뛰었으면 MINORS).
 * 타자는 atBats·hits·homeRuns·avg, 투수는 outs·inningsText·era·strikeouts·saves·holds를 쓴다.
 */
public record LevelTotals(
        String level,
        int games,
        int atBats,
        int hits,
        int homeRuns,
        @Nullable Double avg,
        int outs,
        @Nullable String inningsText,
        @Nullable Double era,
        int strikeouts,
        int saves,
        int holds) {
}
