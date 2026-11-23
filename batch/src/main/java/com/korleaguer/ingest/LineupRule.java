package com.korleaguer.ingest;

import com.korleaguer.client.MlbBoxscore;
import com.korleaguer.domain.Lineup;

/**
 * 박스스코어로 선발·교체를 정한다.
 * <ul>
 *   <li>타자(타순이 있으면, 투타겸업도): 타순이 "00"으로 끝나면(100~900) 선발, 그 밖의 타순(301 등)은 교체</li>
 *   <li>투수(타순이 없고 그 편 pitchers에 있으면): 첫 번째 투수면 선발, 그 밖은 교체</li>
 *   <li>타순도 없고 투수 목록에도 없지만 isSubstitute면 교체(대주자 등)</li>
 *   <li>박스스코어에 없거나 뛰지 않았으면 null</li>
 * </ul>
 */
final class LineupRule {

    private LineupRule() {
    }

    static Lineup of(MlbBoxscore box, long personId) {
        if (box == null) {
            return null;
        }
        for (MlbBoxscore.Side side : box.sides()) {
            MlbBoxscore.Player line = side.player(personId);
            if (line != null) {
                return of(side, line, personId);
            }
        }
        return null;
    }

    private static Lineup of(MlbBoxscore.Side side, MlbBoxscore.Player line, long personId) {
        String battingOrder = line.battingOrder();
        if (battingOrder != null && !battingOrder.isBlank()) {
            return battingOrder.trim().endsWith("00") ? Lineup.STARTER : Lineup.SUB;
        }
        if (side.pitchers() != null && side.pitchers().contains(personId)) {
            return side.pitchers().getFirst() == personId ? Lineup.STARTER : Lineup.SUB;
        }
        if (line.gameStatus() != null && Boolean.TRUE.equals(line.gameStatus().isSubstitute())) {
            return Lineup.SUB;
        }
        return null;
    }
}
