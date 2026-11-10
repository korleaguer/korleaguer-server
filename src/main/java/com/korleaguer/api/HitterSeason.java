package com.korleaguer.api;

/** 타자 시즌 성적. 비율은 소수(0.276)이고 계산할 수 없으면 null이다. photoUrl은 공식 사진 주소(없으면 null). */
public record HitterSeason(
        long playerId,
        String nameKo,
        String photoUrl,
        String teamKo,
        String teamAbbr,
        int games,
        int plateAppearances,
        int atBats,
        int hits,
        int doubles,
        int homeRuns,
        int rbi,
        int walks,
        int strikeouts,
        int stolenBases,
        Double avg,
        Double obp,
        Double slg,
        Double ops) {
}
