package com.korleaguer.api;

import com.korleaguer.domain.Appearance;
import com.korleaguer.domain.Batting;
import com.korleaguer.domain.Game;
import com.korleaguer.domain.Pitching;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.Team;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SeasonStatsTest {

    @Test
    void eraIgnoresGamesWithUnknownOuts() {
        Player player = new Player("고우석");
        ReflectionTestUtils.setField(player, "id", 5L);
        Team team = new Team(142L);
        team.update("MIN", "Minnesota Twins");

        Appearance known = pitched(player, team, 1L, new Pitching(3, 1, 0, 0, 0, 2, 0, 15, 0, 0, 1));
        // 아웃 수를 모르는 경기의 자책점 2점은 평균자책점에 넣지 않는다.
        Appearance unknownOuts = pitched(player, team, 2L, new Pitching(null, 2, 2, 2, 1, 0, 0, 21, 0, 1, 0));

        PitcherSeason season = SeasonStats.pitcher(player, null, team, List.of(known, unknownOuts), 0);

        assertThat(season.games()).isEqualTo(2);
        assertThat(season.outs()).isEqualTo(3);
        assertThat(season.era()).isEqualTo(0.0);
        assertThat(season.losses()).isEqualTo(1);
        assertThat(season.teamAbbr()).isEqualTo("MIN");
    }

    @Test
    void hitStreakSkipsGamesWithoutAtBats() {
        // 오래된 순으로 넣는다: 1안타, 4타수 무안타, 1안타, 타격 기록 없음, 볼넷만(0타수 0안타), 2안타
        // 최신부터 2안타 → (건너뜀) → (건너뜀) → 1안타 → 무안타에서 멈춤 = 2
        List<Appearance> games = List.of(
                batted(1, batting(4, 1)),
                batted(2, batting(4, 0)),
                batted(3, batting(3, 1)),
                batted(4, null),
                batted(5, batting(0, 0)),
                batted(6, batting(4, 2)));

        assertThat(SeasonStats.hitStreak(games)).isEqualTo(2);
        assertThat(SeasonStats.hitStreak(List.of())).isZero();
    }

    @Test
    void scorelessStreakStopsAtRunsOrUnknown() {
        // 오래된 순으로 넣는다. 최신부터 센다.
        List<Appearance> scorelessThenRun = List.of(
                pitchedOn(1, runs(1)), pitchedOn(2, runs(0)), pitchedOn(3, runs(0)));
        List<Appearance> unknownRuns = List.of(
                pitchedOn(1, runs(0)), pitchedOn(2, runs(null)), pitchedOn(3, runs(0)));

        assertThat(SeasonStats.scorelessStreak(scorelessThenRun)).isEqualTo(2);
        assertThat(SeasonStats.scorelessStreak(unknownRuns)).isEqualTo(1);
        // 투구 기록 없는 출전(대주자 등)은 건너뛴다.
        List<Appearance> pinchRunner = List.of(
                pitchedOn(1, runs(1)), pitchedOn(2, runs(0)), pitchedOn(3, null), pitchedOn(4, runs(0)));
        assertThat(SeasonStats.scorelessStreak(pinchRunner)).isEqualTo(2);
    }

    private static Appearance pitched(Player player, Team team, long gameId, Pitching pitching) {
        Appearance appearance = new Appearance(player, new Game(gameId));
        appearance.update(team, null, pitching);
        return appearance;
    }

    // gameId가 클수록 늦은 경기
    private static Appearance batted(long gameId, Batting batting) {
        Appearance appearance = new Appearance(new Player("이정후"), gameOn(gameId));
        appearance.update(null, batting, null);
        return appearance;
    }

    private static Appearance pitchedOn(long gameId, Pitching pitching) {
        Appearance appearance = new Appearance(new Player("고우석"), gameOn(gameId));
        appearance.update(null, null, pitching);
        return appearance;
    }

    private static Game gameOn(long gameId) {
        Game game = new Game(gameId);
        game.update(2026, "regular", Instant.parse("2026-09-01T00:00:00Z").plus(Duration.ofDays(gameId)),
                null, null, null, null, Game.STATUS_FINAL, null);
        return game;
    }

    private static Batting batting(int atBats, int hits) {
        return new Batting(atBats, 0, hits, 0, 0, 0, 0, 0, 0, 0, 0, 0, atBats, hits);
    }

    private static Pitching runs(Integer runs) {
        return new Pitching(3, 0, runs, runs, 0, 0, 0, 10, 0, 0, 0);
    }
}
