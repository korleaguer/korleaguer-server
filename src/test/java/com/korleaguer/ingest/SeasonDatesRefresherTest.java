package com.korleaguer.ingest;

import com.korleaguer.client.MlbApiException;
import com.korleaguer.client.MlbBlockedException;
import com.korleaguer.client.MlbSeason;
import com.korleaguer.client.SampleMlbClient;
import com.korleaguer.config.CurrentSeason;
import com.korleaguer.domain.SeasonDates;
import com.korleaguer.domain.SeasonDatesRepository;
import com.korleaguer.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 시즌 날짜 받기: 올해·내년을 하루 한 번, 발표 전인 해, 실패한 해만 다시, 403/429 중단, 시즌을 고정하면 묻지 않음 */
class SeasonDatesRefresherTest {

    // 미국 동부로는 11/9 밤이다(올해 = 2026).
    private static final Instant NOW = Instant.parse("2026-11-10T03:00:00Z");
    private static final MlbSeason SEASON_2026 = new MlbSeason("2026", "2026-03-25", "2026-09-27", "2026-10-28");
    private static final MlbSeason SEASON_2027 = new MlbSeason("2027", "2027-03-25", "2027-09-26", "2027-10-31");

    private final MutableClock clock = new MutableClock(NOW);
    private final SeasonDatesRepository seasonDates = mock(SeasonDatesRepository.class);
    private final IngestWriter writer = mock(IngestWriter.class);
    private final CurrentSeason currentSeason = mock(CurrentSeason.class);
    private final List<Integer> asked = new ArrayList<>();

    /** 물은 해를 적어 두는 샘플 클라이언트. failYears는 5xx, blockYears는 403을 흉내 낸다. */
    private Set<Integer> failYears = Set.of();
    private Set<Integer> blockYears = Set.of();
    private SampleMlbClient client = client(List.of(SEASON_2026, SEASON_2027));

    private SeasonDatesRefresher refresher;

    @BeforeEach
    void setUp() {
        when(seasonDates.findById(anyInt())).thenReturn(Optional.empty());
        when(writer.saveSeasonDates(anyInt(), any(), any())).thenReturn(true);
        refresher = new SeasonDatesRefresher(client, seasonDates, writer, currentSeason, clock);
    }

    @Test
    void storesThisYearAndNextYearOncePerUtcDay() {
        assertThat(refresher.refresh()).isEqualTo("시즌 날짜 2026·2027");
        assertThat(asked).containsExactly(2026, 2027);
        verify(writer).saveSeasonDates(2026, SEASON_2026, NOW);
        verify(writer).saveSeasonDates(2027, SEASON_2027, NOW);
        // 새 개막일을 바로 쓰도록 지금 시즌이 기억한 값을 버린다.
        verify(currentSeason).forget();

        // 같은 날(UTC) 다시 돌면 묻지 않는다.
        clock.advance(Duration.ofHours(12));
        assertThat(refresher.refresh()).isEmpty();
        assertThat(asked).hasSize(2);

        // 다음 날(UTC) 다시 묻는다.
        clock.set(Instant.parse("2026-11-11T00:00:00Z"));
        refresher.refresh();
        assertThat(asked).containsExactly(2026, 2027, 2026, 2027);
    }

    @Test
    void yearsAlreadyFetchedTodayAreNotAskedAfterRestart() {
        // 앱을 다시 띄운 상황: 2026은 오늘 받았고 2027은 어제 받았다.
        when(seasonDates.findById(2026)).thenReturn(Optional.of(fetchedAt(2026, "2026-11-10T00:30:00Z")));
        when(seasonDates.findById(2027)).thenReturn(Optional.of(fetchedAt(2027, "2026-11-09T23:59:00Z")));

        assertThat(refresher.refresh()).isEqualTo("시즌 날짜 2027");
        assertThat(asked).containsExactly(2027);
    }

    @Test
    void unannouncedYearIsNotSavedAndNotAskedAgainToday() {
        client = client(List.of(SEASON_2026));
        refresher = new SeasonDatesRefresher(client, seasonDates, writer, currentSeason, clock);

        assertThat(refresher.refresh()).isEqualTo("시즌 날짜 2026, 발표 전 2027");
        verify(writer, never()).saveSeasonDates(eq(2027), any(), any());

        clock.advance(Duration.ofHours(1));
        assertThat(refresher.refresh()).isEmpty();
        assertThat(asked).containsExactly(2026, 2027);
    }

    @Test
    void failedYearIsAskedAgainNextRunWithoutBreakingIt() {
        failYears = Set.of(2027);

        assertThat(refresher.refresh()).isEqualTo("시즌 날짜 2026, 실패 1개");
        verify(writer).saveSeasonDates(2026, SEASON_2026, NOW);

        // 다음 실행(12분 뒤): 2026은 오늘 받았으니 2027만 다시 묻는다.
        when(seasonDates.findById(2026)).thenReturn(Optional.of(fetchedAt(2026, NOW.toString())));
        failYears = Set.of();
        clock.advance(Duration.ofMinutes(12));
        assertThat(refresher.refresh()).isEqualTo("시즌 날짜 2027");
        assertThat(asked).containsExactly(2026, 2027, 2027);
    }

    @Test
    void blockedStopsTheRun() {
        blockYears = Set.of(2026);

        assertThatThrownBy(() -> refresher.refresh()).isInstanceOf(MlbBlockedException.class);
        assertThat(asked).containsExactly(2026);
        verify(writer, never()).saveSeasonDates(anyInt(), any(), any());
    }

    @Test
    void fixedSeasonNeedsNoDates() {
        when(currentSeason.isFixed()).thenReturn(true);

        assertThat(refresher.refresh()).isEmpty();
        assertThat(asked).isEmpty();
    }

    private SampleMlbClient client(List<MlbSeason> seasons) {
        return new SampleMlbClient(SampleMlbClient.Data.empty().withSeasons(seasons)) {
            @Override
            public MlbSeason getSeason(int season) {
                asked.add(season);
                MlbSeason found = super.getSeason(season);
                if (blockYears.contains(season)) {
                    throw new MlbBlockedException("403", null);
                }
                if (failYears.contains(season)) {
                    throw new MlbApiException("502", null);
                }
                return found;
            }
        };
    }

    private static SeasonDates fetchedAt(int season, String instant) {
        SeasonDates dates = new SeasonDates(season);
        dates.update(LocalDate.of(season, 3, 25), LocalDate.of(season, 9, 27), null, Instant.parse(instant));
        return dates;
    }
}
