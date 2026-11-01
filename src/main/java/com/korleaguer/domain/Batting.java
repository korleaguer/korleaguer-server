package com.korleaguer.domain;

import jakarta.persistence.Embeddable;

/** 한 경기 타격 기록. 칸마다 null일 수 있다(모든 칸이 null이면 JPA가 Batting 자체를 null로 읽는다). */
@Embeddable
public record Batting(
        Integer atBats,
        Integer runs,
        Integer hits,
        Integer doubles,
        Integer triples,
        Integer homeRuns,
        Integer rbi,
        Integer walks,
        Integer strikeouts,
        Integer hitByPitch,
        Integer stolenBases,
        Integer sacFlies,
        Integer plateAppearances,
        Integer totalBases) {

    /** 루타. 값이 없으면 1루타 + 2×2루타 + 3×3루타 + 4×홈런으로 구한다. */
    public int totalBasesOrComputed() {
        if (totalBases != null) {
            return totalBases;
        }
        int singles = orZero(hits) - orZero(doubles) - orZero(triples) - orZero(homeRuns);
        return singles + 2 * orZero(doubles) + 3 * orZero(triples) + 4 * orZero(homeRuns);
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
