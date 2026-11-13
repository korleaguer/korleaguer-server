package com.korleaguer.api;

/**
 * 타자 시즌 성적(메이저 정규시즌). 비율은 소수(0.276)이고 계산할 수 없으면 null이다. photoUrl은 공식 사진 주소(없으면 null).
 * minorGames는 같은 시즌 마이너(트리플A ~ 루키)에서 타자로 나온 경기 수(받기 전이면 0),
 * levelKo는 마이너 시즌 성적 줄일 때 단계(트리플A 등, 단계가 섞였으면 "마이너". teamKo는 마지막 마이너 팀)이고 메이저 줄이면 null이다.
 * mainlyMinors는 마이너 경기가 메이저 경기(games)보다 많은지다.
 */
public record HitterSeason(
        long playerId,
        String nameKo,
        String photoUrl,
        String teamKo,
        String teamAbbr,
        int games,
        int minorGames,
        boolean mainlyMinors,
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
        Double ops,
        String levelKo) {
}
