package com.korleaguer.support;

import com.korleaguer.domain.Batting;
import com.korleaguer.domain.Pitching;
import com.korleaguer.domain.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GameSummaryTest {

    @Test
    @DisplayName("타자 한 줄에는 타수·안타·2루타·홈런·타점·볼넷이 나온다")
    void hitterLine() {
        Batting batting = hitter(4, 2, 1, 0, 1, 2, 1, 0);

        assertThat(GameSummary.played(Role.HITTER, batting, null))
                .isEqualTo("4타수 2안타(2루타 1) 1홈런 2타점 1볼넷");
    }

    @Test
    @DisplayName("안타가 없으면 무안타라고 쓴다")
    void hitterWithoutHits() {
        Batting batting = hitter(3, 0, 0, 0, 0, 0, 1, 0);

        assertThat(GameSummary.hitting(batting)).isEqualTo("3타수 무안타 1볼넷");
    }

    @Test
    @DisplayName("3루타와 도루도 한 줄에 나온다")
    void hitterWithTripleAndSteal() {
        Batting batting = hitter(4, 3, 0, 1, 0, 2, 0, 1);

        assertThat(GameSummary.hitting(batting)).isEqualTo("4타수 3안타(3루타 1) 2타점 1도루");
    }

    @Test
    void starterWithWin() {
        Pitching pitching = new Pitching(18, 7, 2, 2, 1, 7, 0, 95, 1, 0, 0);

        assertThat(GameSummary.played(Role.PITCHER, null, pitching))
                .isEqualTo("6이닝 7피안타 2실점(2자책) 7탈삼진 1볼넷 · 승");
    }

    @Test
    void relieverScoreless() {
        Pitching pitching = new Pitching(3, 1, 0, 0, 0, 2, 0, 15, 0, 0, 0);

        assertThat(GameSummary.pitching(pitching)).isEqualTo("1이닝 1피안타 무실점 2탈삼진");
    }

    @Test
    void relieverPartialInningWithLoss() {
        Pitching pitching = new Pitching(2, 2, 2, 2, 1, 0, 0, 21, 0, 1, 0);

        assertThat(GameSummary.pitching(pitching)).isEqualTo("⅔이닝 2피안타 2실점(2자책) 1볼넷 · 패");
    }

    @Test
    void absentWhenTeamPlayedButNoRecord() {
        assertThat(GameSummary.notPlayed("final", true)).isEqualTo("결장");
    }

    @Test
    void notAbsentWhenStatsAreUnavailableOrGameNotFinished() {
        assertThat(GameSummary.notPlayed("final", false)).isEqualTo("기록 없음");
        assertThat(GameSummary.notPlayed("scheduled", true)).isEqualTo("경기 전");
        assertThat(GameSummary.notPlayed("postponed", true)).isEqualTo("연기");
    }

    /** 타수·안타·2루타·3루타·홈런·타점·볼넷·도루. 문구에 쓰지 않는 칸은 0이다 */
    private static Batting hitter(int atBats, int hits, int doubles, int triples, int homeRuns, int rbi, int walks,
                                  int stolenBases) {
        return new Batting(atBats, 0, hits, doubles, triples, homeRuns, rbi, walks, 0, 0, stolenBases, 0,
                atBats + walks, hits + doubles + 2 * triples + 3 * homeRuns);
    }
}
