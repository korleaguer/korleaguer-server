package com.korleaguer.api;

/** 한 경기 타격 숫자. 빈 칸(null)은 0으로 둔다. */
public record GameBatting(
        int atBats,
        int hits,
        int homeRuns,
        int rbi,
        int walks,
        int stolenBases) {
}
