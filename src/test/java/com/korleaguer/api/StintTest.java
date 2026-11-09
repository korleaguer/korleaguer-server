package com.korleaguer.api;

import com.korleaguer.domain.Appearance;
import com.korleaguer.domain.Game;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.Team;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 결장 줄을 붙이는 기간(뛴 기간) */
class StintTest {

    private static final Team MIN = team(142L, "MIN");

    @Test
    void currentTeamKeepsTheEndOpen() {
        List<GameLineAssembler.Stint> stints = GameLineAssembler.stints(List.of(on(1), on(3)), MIN);

        assertThat(stints).singleElement().satisfies(stint -> {
            assertThat(stint.covers(day(2))).isTrue();
            assertThat(stint.covers(day(30))).isTrue(); // 마지막 출전 뒤도 결장
        });
    }

    @Test
    void minorLeaguerClosesTheEndAtLastAppearance() {
        // 마이너로 간 선수는 지금 팀을 넘기지 않는다(stintsByPlayer가 선수 level로 정한다).
        List<GameLineAssembler.Stint> stints = GameLineAssembler.stints(List.of(on(1), on(3)), null);

        assertThat(stints).singleElement().satisfies(stint -> {
            assertThat(stint.covers(day(2))).isTrue();
            assertThat(stint.covers(day(30))).isFalse();
        });
    }

    private static Appearance on(int day) {
        Game game = new Game((long) day);
        game.update(2026, "regular", day(day), MIN, MIN, 1, 2, Game.STATUS_FINAL, null);
        Appearance appearance = new Appearance(new Player("고우석"), game);
        appearance.update(MIN, null, null);
        return appearance;
    }

    private static Instant day(int day) {
        return Instant.parse("2026-08-01T23:00:00Z").plus(Duration.ofDays(day));
    }

    private static Team team(long id, String abbreviation) {
        Team team = new Team(id);
        team.update(abbreviation, abbreviation);
        return team;
    }
}
