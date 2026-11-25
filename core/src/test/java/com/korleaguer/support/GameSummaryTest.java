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
        // given
        Batting batting = hitter(4, 2, 1, 0, 1, 2, 1, 0);

        // when // then
        assertThat(GameSummary.played(Role.HITTER, batting, null))
                .isEqualTo("4타수 2안타(2루타 1) 1홈런 2타점 1볼넷");
    }

    @Test
    @DisplayName("안타가 없으면 무안타라고 쓴다")
    void hitterWithoutHits() {
        // given
        Batting batting = hitter(3, 0, 0, 0, 0, 0, 1, 0);

        // when // then
        assertThat(GameSummary.hitting(batting)).isEqualTo("3타수 무안타 1볼넷");
    }

    @Test
    @DisplayName("3루타와 도루도 한 줄에 나온다")
    void hitterWithTripleAndSteal() {
        // given
        Batting batting = hitter(4, 3, 0, 1, 0, 2, 0, 1);

        // when // then
        assertThat(GameSummary.hitting(batting)).isEqualTo("4타수 3안타(3루타 1) 2타점 1도루");
    }

    @Test
    @DisplayName("승리 투수 한 줄에는 이닝·피안타·실점·탈삼진·볼넷과 승이 나온다")
    void starterWithWin() {
        // given
        Pitching pitching = new Pitching(18, 7, 2, 2, 1, 7, 0, 95, 1, 0, 0);

        // when // then
        assertThat(GameSummary.played(Role.PITCHER, null, pitching))
                .isEqualTo("6이닝 7피안타 2실점(2자책) 7탈삼진 1볼넷 · 승");
    }

    @Test
    @DisplayName("점수를 안 준 투수는 무실점이라고 쓰고, 볼넷이 없으면 볼넷을 뺀다")
    void relieverScoreless() {
        // given
        Pitching pitching = new Pitching(3, 1, 0, 0, 0, 2, 0, 15, 0, 0, 0);

        // when // then
        assertThat(GameSummary.pitching(pitching)).isEqualTo("1이닝 1피안타 무실점 2탈삼진");
    }

    @Test
    @DisplayName("1이닝을 못 채운 패전 투수는 ⅔이닝과 패로 쓴다")
    void relieverPartialInningWithLoss() {
        // given
        Pitching pitching = new Pitching(2, 2, 2, 2, 1, 0, 0, 21, 0, 1, 0);

        // when // then
        assertThat(GameSummary.pitching(pitching)).isEqualTo("⅔이닝 2피안타 2실점(2자책) 1볼넷 · 패");
    }

    @Test
    @DisplayName("기록을 받은 끝난 경기에 선수 기록이 없으면 결장이라고 쓴다")
    void absentWhenTeamPlayedButNoRecord() {
        // when // then
        assertThat(GameSummary.notPlayed("final", true)).isEqualTo("결장");
    }

    @Test
    @DisplayName("기록을 못 받은 경기는 기록 없음, 시작 전은 경기 전, 미뤄지면 연기로 쓴다")
    void notAbsentWhenStatsAreUnavailableOrGameNotFinished() {
        // when // then
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
