package com.korleaguer.api;

import com.korleaguer.domain.Appearance;
import com.korleaguer.domain.Game;
import com.korleaguer.domain.Pitching;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.Team;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SeasonStatsTest {

    @Test
    void eraIgnoresGamesWithUnknownOuts() {
        Player player = new Player("고우석");
        ReflectionTestUtils.setField(player, "id", 5L);
        Team team = new Team(17L);
        team.update("MIN", "Minnesota Twins", "Minnesota", "American");

        Appearance known = pitched(player, team, 1L, new Pitching(3, 1, 0, 0, 0, 2, 0, 15, 0, 0, 1));
        // 아웃 수를 모르는 경기의 자책점 2점은 평균자책점에 넣지 않는다.
        Appearance unknownOuts = pitched(player, team, 2L, new Pitching(null, 2, 2, 2, 1, 0, 0, 21, 0, 1, 0));

        PitcherSeason season = SeasonStats.pitcher(player, null, team, List.of(known, unknownOuts));

        assertThat(season.games()).isEqualTo(2);
        assertThat(season.outs()).isEqualTo(3);
        assertThat(season.era()).isEqualTo(0.0);
        assertThat(season.losses()).isEqualTo(1);
        assertThat(season.teamAbbr()).isEqualTo("MIN");
    }

    private static Appearance pitched(Player player, Team team, long gameId, Pitching pitching) {
        Appearance appearance = new Appearance(player, new Game(gameId));
        appearance.update(team, null, pitching);
        return appearance;
    }
}
