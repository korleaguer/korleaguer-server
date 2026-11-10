package com.korleaguer.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 한 경기 타격 또는 투구 기록(gameLog의 stat, 박스스코어의 stats.batting·stats.pitching). 모든 칸은 null일 수 있다.
 * 투구 기록에서 hits·runs·baseOnBalls·strikeOuts·homeRuns는 내준 값이다. inningsPitched는 "5.2" 같은 문자열이다.
 * 박스스코어에서 뛰지 않은 쪽은 빈 객체({})라 gamesPlayed가 null이다. holds·blownSaves는 투구 gameLog에만 온다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MlbStat(
        Integer gamesPlayed,
        Integer gamesStarted,
        Integer atBats,
        Integer runs,
        Integer hits,
        Integer doubles,
        Integer triples,
        Integer homeRuns,
        Integer rbi,
        Integer baseOnBalls,
        Integer strikeOuts,
        Integer hitByPitch,
        Integer stolenBases,
        Integer sacFlies,
        Integer plateAppearances,
        Integer totalBases,
        Integer outs,
        String inningsPitched,
        Integer earnedRuns,
        Integer numberOfPitches,
        Integer wins,
        Integer losses,
        Integer saves,
        Integer holds,
        Integer blownSaves) {

    /** 그 경기에 이 기록(타격 또는 투구)이 없다. */
    public static boolean isEmpty(MlbStat stat) {
        return stat == null || stat.gamesPlayed() == null;
    }
}
