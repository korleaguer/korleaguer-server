package com.korleaguer.api.game;

import com.korleaguer.api.player.RosterMember;
import com.korleaguer.api.player.RosterProfiles;

import com.korleaguer.config.CurrentSeason;
import com.korleaguer.domain.Appearance;
import com.korleaguer.domain.AppearanceRepository;
import com.korleaguer.domain.Game;
import com.korleaguer.domain.GameRepository;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerMoveRepository;
import com.korleaguer.domain.SeasonType;
import com.korleaguer.domain.Team;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 경기 종류를 모르는 경기는 경기 줄을 만들지 않는다(이슈 #190 리뷰). 수집은 정규시즌·포스트시즌만 저장하지만 game.season_type
 * 칸은 null을 허용한다. 그런 경기는 뛴 기록이 있어도(뛴 경기 줄), 팀 경기여도(결장 줄) 줄이 없어 응답 seasonType이 비지 않고,
 * 줄을 고르다 멈추지도 않는다(Set.of는 null을 물으면 예외였다).
 */
class UnknownSeasonTypeGameTest {

    private static final Team MIN = team(142L, "MIN");
    private static final Team DET = team(116L, "DET");
    private static final Instant FROM = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-31T00:00:00Z");

    @Test
    @DisplayName("경기 종류를 모르는 경기는 뛰었든 결장이든 경기 줄에서 빠진다")
    void gamesWithoutSeasonTypeHaveNoLine() {
        // given
        Player player = new Player("고우석");
        ReflectionTestUtils.setField(player, "id", 5L);
        AppearanceRepository appearances = mock(AppearanceRepository.class);
        List<Appearance> playedGames = List.of(
                played(player, game(7001L, SeasonType.REGULAR.stored(), "2026-09-25T23:10:00Z")),
                played(player, game(7999L, null, "2026-09-27T23:10:00Z")),
                played(player, game(7201L, SeasonType.POSTSEASON.stored(), "2026-10-04T22:08:00Z")));
        when(appearances.findWithGameBetween(any(), any(), any())).thenReturn(playedGames);
        // 결장 줄을 붙일 기간(뛴 팀과 날짜)은 시즌 출전에서 정한다
        when(appearances.findSeason(any(), anyInt(), any())).thenReturn(playedGames);
        GameRepository games = mock(GameRepository.class);
        when(games.findTeamGamesBetween(any(), any(), any())).thenReturn(List.of(
                game(7002L, SeasonType.REGULAR.stored(), "2026-09-26T23:10:00Z"),
                game(7998L, null, "2026-09-28T23:10:00Z")));
        GameLineAssembler assembler = new GameLineAssembler(games, appearances, mock(PlayerMoveRepository.class),
                mock(RosterProfiles.class), mock(CurrentSeason.class));

        // when
        List<GameLine> lines = assembler.lines(List.of(new RosterMember(player, MIN, false)), FROM, TO);

        // then
        // 7201·7001은 뛴 경기, 7002는 결장. 종류가 빈 7999(뛴 경기)·7998(팀 경기)은 줄이 없다
        assertThat(lines).extracting(GameLine::gameId).containsExactly(7201L, 7002L, 7001L);
        assertThat(lines).extracting(GameLine::played).containsExactly(true, false, true);
        assertThat(lines).extracting(GameLine::seasonType)
                .containsExactly(SeasonType.POSTSEASON, SeasonType.REGULAR, SeasonType.REGULAR);
    }

    private static Game game(long gamePk, String seasonType, String start) {
        Game game = new Game(gamePk);
        game.update(2026, seasonType, Instant.parse(start), MIN, DET, 3, 2, Game.STATUS_FINAL, null);
        return game;
    }

    private static Appearance played(Player player, Game game) {
        Appearance appearance = new Appearance(player, game);
        appearance.update(MIN, null, null);
        return appearance;
    }

    private static Team team(long id, String abbreviation) {
        Team team = new Team(id);
        team.update(abbreviation, abbreviation);
        return team;
    }
}
