package com.korleaguer.api.game;

import com.korleaguer.domain.Pitching;
import com.korleaguer.support.BaseballMath;

/**
 * 한 경기 투구 숫자. outs(아웃 수)를 모르면 outs·inningsText가 null이다.
 * 나머지 빈 칸(null)은 0(승·패·세이브는 false)으로 둔다.
 */
public record GamePitching(
        Integer outs,
        String inningsText,
        int runs,
        int earnedRuns,
        int strikeouts,
        boolean win,
        boolean loss,
        boolean save) {

    /** 저장된 투구 기록을 경기 줄 숫자로. 기록이 없으면 null */
    public static GamePitching of(Pitching p) {
        if (p == null) {
            return null;
        }
        Integer outs = p.pitchingOuts();
        return new GamePitching(outs, outs == null ? null : BaseballMath.inningsText(outs),
                orZero(p.runsAllowed()), orZero(p.earnedRuns()), orZero(p.pitcherStrikeouts()),
                orZero(p.wins()) > 0, orZero(p.losses()) > 0, orZero(p.saves()) > 0);
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
