package com.korleaguer.api.season;

import com.korleaguer.domain.MinorGameLog;
import com.korleaguer.domain.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 여러 마이너 경기를 더한 기록의 단계 이름(이슈 #134) */
class MinorLevelNameTest {

    private static final Player GO = new Player("고우석");

    @Test
    @DisplayName("마이너 경기가 모두 트리플A면 단계 이름이 트리플A다")
    void oneLevelKeepsItsName() {
        // when // then
        assertThat(MinorRecords.levelKo(List.of(game(1, "AAA"), game(2, "AAA")))).isEqualTo("트리플A");
    }

    @Test
    @DisplayName("더블A와 트리플A 경기를 더하면 단계 이름이 마이너다")
    void mixedLevelsAreMinors() {
        // when // then
        // 운영 고우석: 더블A(이리)와 트리플A(톨레도·세인트폴)를 더한 평균자책점에 '트리플A'를 붙이지 않는다
        assertThat(MinorRecords.levelKo(List.of(game(1, "AA"), game(2, "AAA")))).isEqualTo("마이너");
    }

    @Test
    @DisplayName("단계를 모르는 경기가 끼거나 모두 모르면 단계 이름이 마이너다")
    void unknownLevelCountsAsALevel() {
        // when // then
        // 단계를 모르는 경기(null)도 한 단계로 센다(성적표 statLevelKo의 SeasonRates.levelKo와 같은 규칙, 이슈 #179 리뷰)
        assertThat(MinorRecords.levelKo(List.of(game(1, "AAA"), game(2, null)))).isEqualTo("마이너");
        assertThat(MinorRecords.levelKo(List.of(game(1, null), game(2, null)))).isEqualTo("마이너");
    }

    @Test
    @DisplayName("마이너 경기가 없으면 단계 이름이 마이너다")
    void noGamesIsMinors() {
        // when // then
        assertThat(MinorRecords.levelKo(List.<MinorGameLog>of())).isEqualTo("마이너");
    }

    @Test
    @DisplayName("홈 화면 마이너 기록은 단계가 섞이면 마이너, 팀은 마지막 경기 팀이다")
    void homeMinorRowUsesTheCombinedName() {
        // given
        ReflectionTestUtils.setField(GO, "id", 5L);

        // when
        HitterSeason row = SeasonStats.minorHitter(GO, null, null, List.of(game(1, "AA"), game(2, "AAA")));

        // then
        assertThat(row.levelKo()).isEqualTo("마이너");
        assertThat(row.teamKo()).isEqualTo("Team 2"); // 팀은 그대로 마지막 경기의 팀
    }

    private static MinorGameLog game(long gamePk, String level) {
        MinorGameLog game = new MinorGameLog(GO, 2026, gamePk);
        game.updateGame(LocalDate.of(2026, 6, 1).plusDays(gamePk), level, "Team " + gamePk, "Opponent", true, true);
        return game;
    }
}
