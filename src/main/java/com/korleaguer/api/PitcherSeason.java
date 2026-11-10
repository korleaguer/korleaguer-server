package com.korleaguer.api;

/**
 * 투수 시즌 성적(메이저 정규시즌). era는 아웃이 0이면 null이다. photoUrl은 공식 사진 주소(없으면 null).
 * minorGames는 같은 시즌 마이너(트리플A ~ 루키) 등판 경기 수(받기 전이면 0),
 * levelKo는 마이너 시즌 성적 줄일 때 단계(트리플A 등, teamKo는 마지막 마이너 팀)이고 메이저 줄이면 null이다.
 * mainlyMinors는 마이너 등판이 메이저 등판(games)보다 많은지다.
 */
public record PitcherSeason(
        long playerId,
        String nameKo,
        String photoUrl,
        String teamKo,
        String teamAbbr,
        int games,
        int minorGames,
        boolean mainlyMinors,
        int outs,
        String inningsText,
        Double era,
        int wins,
        int losses,
        int saves,
        int strikeouts,
        int walks,
        int hitsAllowed,
        String levelKo) {
}
