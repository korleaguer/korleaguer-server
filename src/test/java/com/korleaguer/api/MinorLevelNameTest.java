package com.korleaguer.api;

import com.korleaguer.domain.MinorGameLog;
import com.korleaguer.domain.Player;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 여러 마이너 경기를 더한 기록의 단계 이름(이슈 #134) */
class MinorLevelNameTest {

    private static final Player GO = new Player("고우석");

    @Test
    void oneLevelKeepsItsName() {
        assertThat(MinorRecords.levelKo(List.of(game(1, "AAA"), game(2, "AAA")))).isEqualTo("트리플A");
    }

    @Test
    void mixedLevelsAreMinors() {
        // 운영 고우석: 더블A(이리)와 트리플A(톨레도·세인트폴)를 더한 평균자책점에 '트리플A'를 붙이지 않는다
        assertThat(MinorRecords.levelKo(List.of(game(1, "AA"), game(2, "AAA")))).isEqualTo("마이너");
    }

    @Test
    void noGamesIsMinors() {
        assertThat(MinorRecords.levelKo(List.<MinorGameLog>of())).isEqualTo("마이너");
    }

    @Test
    void homeMinorRowUsesTheCombinedName() {
        ReflectionTestUtils.setField(GO, "id", 5L);
        HitterSeason row = SeasonStats.minorHitter(GO, null, List.of(game(1, "AA"), game(2, "AAA")));

        assertThat(row.levelKo()).isEqualTo("마이너");
        assertThat(row.teamKo()).isEqualTo("Team 2"); // 팀은 그대로 마지막 경기의 팀
    }

    private static MinorGameLog game(long gamePk, String level) {
        MinorGameLog game = new MinorGameLog(GO, 2026, gamePk);
        game.updateGame(LocalDate.of(2026, 6, 1).plusDays(gamePk), level, "Team " + gamePk, "Opponent", true, true);
        return game;
    }
}
