package com.korleaguer.ingest;

import com.korleaguer.client.MlbScheduleGame;
import com.korleaguer.client.MlbStat;
import com.korleaguer.client.MlbTeam;
import com.korleaguer.domain.Batting;
import com.korleaguer.domain.Pitching;
import com.korleaguer.support.BaseballMath;
import com.korleaguer.support.GameStatuses;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** MLB 응답의 값을 우리 값으로 읽는다(날짜 글자, 팀 번호·이름, 타격·투구 기록). 저장 클래스들이 같이 쓴다. */
final class MlbValues {

    private MlbValues() {
    }

    static LocalDate date(String text) {
        if (text == null) {
            return null;
        }
        try {
            return LocalDate.parse(text.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 일정의 경기 상태(GameStatuses 값). 상태가 없으면 "unknown" */
    static String gameState(MlbScheduleGame game) {
        MlbScheduleGame.Status status = game.status();
        return status == null ? "unknown"
                : GameStatuses.of(status.abstractGameState(), status.codedGameState(), status.detailedState());
    }

    static Long teamId(MlbTeam team) {
        return team == null ? null : team.id();
    }

    static String teamName(MlbTeam team) {
        return team == null ? null : team.name();
    }

    static Batting toBatting(MlbStat s) {
        if (MlbStat.isEmpty(s)) {
            return null;
        }
        return new Batting(s.atBats(), s.runs(), s.hits(), s.doubles(), s.triples(), s.homeRuns(), s.rbi(),
                s.baseOnBalls(), s.strikeOuts(), s.hitByPitch(), s.stolenBases(), s.sacFlies(), s.plateAppearances(),
                s.totalBases());
    }

    // 아웃 수는 outs를 쓰고, 없으면 inningsPitched("5.2" = 17아웃)로 구한다.
    static Pitching toPitching(MlbStat s) {
        if (MlbStat.isEmpty(s)) {
            return null;
        }
        Integer outs = s.outs() != null ? s.outs() : BaseballMath.outsFromIp(innings(s.inningsPitched()));
        return new Pitching(outs, s.hits(), s.runs(), s.earnedRuns(), s.baseOnBalls(), s.strikeOuts(), s.homeRuns(),
                s.numberOfPitches(), s.wins(), s.losses(), s.saves());
    }

    private static Double innings(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Double.valueOf(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
