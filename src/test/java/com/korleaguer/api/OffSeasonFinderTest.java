package com.korleaguer.api;

import com.korleaguer.config.CurrentSeason;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.SeasonDates;
import com.korleaguer.domain.SeasonDatesRepository;
import com.korleaguer.ingest.GameIngest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 비시즌 판단: 최근 일주일 안에 시작한 우리 선수 구단 경기가 없고 정규시즌 기간도 아니면 비시즌.
 * 다음 개막일과 남은 날(미국 날짜), 개막일을 모르면 비워 둔다.
 */
class OffSeasonFinderTest {

    // 미국 동부 2026-11-11 07:00. 2026 정규시즌(3/25~9/27)이 끝났다.
    private static final Instant NOV_11 = Instant.parse("2026-11-11T12:00:00Z");

    private final GameIngest gameIngest = mock(GameIngest.class);
    private final SeasonDatesRepository seasonDates = mock(SeasonDatesRepository.class);
    private final CurrentSeason currentSeason = mock(CurrentSeason.class);
    private final OffSeasonFinder finder = new OffSeasonFinder(gameIngest, seasonDates, currentSeason);

    @BeforeEach
    void setUp() {
        when(currentSeason.current()).thenReturn(2026);
        when(gameIngest.usDate(any())).thenAnswer(inv -> LocalDate.ofInstant(inv.getArgument(0), GameIngest.US_EASTERN));
        when(seasonDates.findById(anyInt())).thenReturn(Optional.empty());
        when(seasonDates.findById(2026)).thenReturn(Optional.of(dates(2026, "2026-03-25", "2026-09-27")));
    }

    @Test
    void ourTeamsPlayedThisWeekSoInSeason() {
        when(gameIngest.hasRecentGames(NOV_11, OffSeasonFinder.QUIET_DAYS)).thenReturn(true);

        assertThat(finder.find(roster(1L, 2L), NOV_11)).isNull();
    }

    @Test
    void offSeasonCountsDaysToNextOpeningDay() {
        when(seasonDates.findById(2027)).thenReturn(Optional.of(dates(2027, "2027-03-25", "2027-09-26")));

        assertThat(finder.find(roster(1L, 2L, 3L), NOV_11)).isEqualTo(
                new OffSeason(2026, 2027, LocalDate.parse("2027-03-25"), 134, List.of(1L, 2L, 3L)));
    }

    @Test
    void unannouncedOpeningDayStaysEmpty() {
        OffSeason off = finder.find(roster(1L), NOV_11);

        assertThat(off.nextSeason()).isEqualTo(2027);
        assertThat(off.nextOpeningDay()).isNull();
        assertThat(off.daysToOpening()).isNull();
    }

    @Test
    void quietWeekInsideTheRegularSeasonIsStillInSeason() {
        // 7월: 우리 선수가 모두 부상·마이너라 일주일 동안 구단 경기가 없어도 시즌이 끝난 게 아니다
        assertThat(finder.find(roster(1L), Instant.parse("2026-07-15T12:00:00Z"))).isNull();
    }

    @Test
    void eliminatedFromThePostseasonIsOffSeason() {
        // 10/20: 포스트시즌은 이어지지만 우리 선수 구단 경기가 일주일 없다(모두 탈락)
        when(seasonDates.findById(2027)).thenReturn(Optional.of(dates(2027, "2027-03-25", "2027-09-26")));

        OffSeason off = finder.find(roster(1L), Instant.parse("2026-10-20T12:00:00Z"));

        assertThat(off.season()).isEqualTo(2026);
        assertThat(off.daysToOpening()).isEqualTo(156);
    }

    @Test
    void dayBeforeOpeningIsOneDayLeftAndOpeningDayStartsTheSeason() {
        when(seasonDates.findById(2027)).thenReturn(Optional.of(dates(2027, "2027-03-25", "2027-09-26")));

        // 개막 전날(미국 날짜): 지금 시즌은 아직 2026이다
        OffSeason off = finder.find(roster(1L), Instant.parse("2027-03-24T13:00:00Z"));
        assertThat(off.nextOpeningDay()).isEqualTo(LocalDate.parse("2027-03-25"));
        assertThat(off.daysToOpening()).isEqualTo(1);

        // 개막일(미국 날짜)이 되면 지금 시즌이 2027로 넘어가고 정규시즌 기간이라, 첫 경기 전이어도 시즌 중이다
        when(currentSeason.current()).thenReturn(2027);
        assertThat(finder.find(roster(1L), Instant.parse("2027-03-25T13:00:00Z"))).isNull();
    }

    private static List<RosterMember> roster(Long... ids) {
        return Arrays.stream(ids).map(id -> {
            Player player = mock(Player.class);
            when(player.getId()).thenReturn(id);
            return new RosterMember(player, null, false);
        }).toList();
    }

    private static SeasonDates dates(int season, String start, String end) {
        SeasonDates dates = new SeasonDates(season);
        dates.update(LocalDate.parse(start), LocalDate.parse(end), null, Instant.parse(season + "-01-02T00:00:00Z"));
        return dates;
    }
}
