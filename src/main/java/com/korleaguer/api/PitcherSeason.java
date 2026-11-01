package com.korleaguer.api;

/** 투수 시즌 성적. era는 아웃이 0이면 null이다. photoUrl은 공식 사진 주소(없으면 null). */
public record PitcherSeason(
        long playerId,
        String nameKo,
        String photoUrl,
        String teamKo,
        String teamAbbr,
        int games,
        int outs,
        String inningsText,
        Double era,
        int wins,
        int losses,
        int saves,
        int strikeouts,
        int walks,
        int hitsAllowed) {
}
