package com.korleaguer.api.season;

import com.korleaguer.api.article.LinkedTeamsCounter;

import com.korleaguer.api.player.RosterMember;

import com.korleaguer.config.NewsProperties;
import com.korleaguer.domain.AppearanceRepository;
import com.korleaguer.domain.MinorGameLogRepository;
import com.korleaguer.domain.NewsArticleRepository;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerLeaguePosition;
import com.korleaguer.domain.PlayerLeaguePositionRepository;
import com.korleaguer.domain.PlayerMoveRepository;
import com.korleaguer.domain.PlayerSeasonMetric;
import com.korleaguer.domain.PlayerSeasonMetricRepository;
import com.korleaguer.domain.Role;
import com.korleaguer.domain.SeasonDatesRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 성적표의 'MLB 안 위치'(saber, 이슈 #179): 선수 화면과 같은 줄로 만든 같은 값, 선수 역할의 묶음만, 못 읽으면 빠진다.
 * 이정후·고우석 값은 운영 2026 값(선수 화면 saber)을 옮긴 것이고, 타자의 투수 줄은 지어낸 값이다.
 */
class SeasonReportFinderSaberTest {

    private static final Instant NOW = Instant.parse("2026-11-17T11:00:00Z");

    private final PlayerLeaguePositionRepository positions = mock(PlayerLeaguePositionRepository.class);
    private final SeasonReportFinder finder = new SeasonReportFinder(mock(AppearanceRepository.class),
            mock(MinorGameLogRepository.class), mock(PlayerMoveRepository.class), mock(PlayerSeasonMetricRepository.class),
            positions, mock(SeasonDatesRepository.class), mock(NewsArticleRepository.class),
            mock(LinkedTeamsCounter.class), new NewsProperties(true, "0 */30 * * * *", "korleaguer/test",
            List.of("korleaguer"), List.of()), mock(LeagueEnvironments.class), Clock.fixed(NOW, ZoneOffset.UTC),
            // 단위 시험이라 따로 읽는 트랜잭션은 흉내만 낸다(트랜잭션 관리자 mock)
            new OptionalReads(mock(PlatformTransactionManager.class)));

    @Test
    @DisplayName("MLB 안 위치는 선수의 타자·투수 역할에 맞는 기록에서만 가져온다")
    void eachPlayerGetsTheRowOfHisOwnRoleLikeThePlayerPage() {
        // given
        Player lee = player(1, Role.HITTER);
        Player go = player(5, Role.PITCHER);
        PlayerLeaguePosition leeHitting = hitting(lee, new PlayerLeaguePosition.Hitting(579, 101.8, 0.97, 1.25, 0.68,
                -11.27, 465, 40, 59, 60, 20));
        // 타자에게 투수 줄도 있으면(야수 등판) 선수 화면처럼 역할(타자)의 줄만 쓴다
        PlayerLeaguePosition leePitching = pitching(lee, new PlayerLeaguePosition.Pitching(3, 0.0, 50.0, 0.0, 0.0, 0.0,
                464, null, null, null, null));
        PlayerLeaguePosition goPitching = pitching(go, new PlayerLeaguePosition.Pitching(12, 216.1, 209.7, 22.7, 9.1,
                -0.14, 464, null, null, null, null));
        when(positions.findSeason(any(), eq(2026))).thenReturn(List.of(leeHitting, leePitching, goPitching));

        // when
        Map<Long, PlayerSaber> sabers = finder.sabers(List.of(member(lee), member(go)), 2026);

        // then
        assertThat(sabers).containsOnlyKeys(1L, 5L);
        assertThat(sabers.get(1L)).isEqualTo(PlayerSaber.of(leeHitting));
        PlayerSaber.Hitter hitter = (PlayerSaber.Hitter) sabers.get(1L);
        assertThat(hitter.wrcPlus()).isEqualTo(101.8);
        assertThat(hitter.percentiles()).isEqualTo(new PlayerSaber.HitterPercentiles(40, 59, 60, 20));
        // 고우석 메이저 4이닝: 값은 있고 위치는 10이닝 미만이라 줄에 없다(선수 화면과 같다)
        PlayerSaber.Pitcher pitcher = (PlayerSaber.Pitcher) sabers.get(5L);
        assertThat(pitcher.eraMinus()).isEqualTo(216.1);
        assertThat(pitcher.inningsOuts()).isEqualTo(12);
        assertThat(pitcher.percentiles()).isNull();
    }

    @Test
    @DisplayName("MLB 기록이 비어 있거나 읽지 못하면 그 선수의 MLB 안 위치가 없다")
    void playersWithoutAnMlbRecordOrUnreadableRowsHaveNoSaber() {
        // given
        Player bae = player(6, Role.HITTER);
        // 그날 리그 기록에 없으면(마이너에만 있음) 줄은 '받았음' 표시로 남고 값은 비어 있다
        PlayerLeaguePosition empty = new PlayerLeaguePosition(bae, 2026, PlayerSeasonMetric.Group.HITTING);
        empty.update((PlayerLeaguePosition.Hitting) null, NOW);
        when(positions.findSeason(any(), eq(2026))).thenReturn(List.of(empty));

        // when // then
        assertThat(finder.sabers(List.of(member(bae)), 2026)).isEmpty();

        // given
        // 못 읽으면 위치만 빠진다(성적표는 그대로)
        when(positions.findSeason(any(), eq(2026))).thenThrow(new IllegalStateException("db unavailable"));

        // when // then
        assertThat(finder.sabers(List.of(member(bae)), 2026)).isEmpty();
    }

    private static Player player(long id, Role role) {
        Player player = mock(Player.class);
        when(player.getId()).thenReturn(id);
        when(player.role()).thenReturn(role);
        return player;
    }

    private static RosterMember member(Player player) {
        return new RosterMember(player, null, false);
    }

    private static PlayerLeaguePosition hitting(Player player, PlayerLeaguePosition.Hitting values) {
        PlayerLeaguePosition row = new PlayerLeaguePosition(player, 2026, PlayerSeasonMetric.Group.HITTING);
        row.update(values, NOW);
        return row;
    }

    private static PlayerLeaguePosition pitching(Player player, PlayerLeaguePosition.Pitching values) {
        PlayerLeaguePosition row = new PlayerLeaguePosition(player, 2026, PlayerSeasonMetric.Group.PITCHING);
        row.update(values, NOW);
        return row;
    }
}
