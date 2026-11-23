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

/**
 * 시즌 날짜 받기: 올해 개막일 전에는 올해 날짜, 정규시즌이 끝난 뒤에는 내년 날짜를 하루 한 번. 시즌 중·오늘 받은 뒤엔 묻지 않음,
 * 발표 전인 해, 실패하면 다음 실행에 다시, 403/429 중단, 시즌을 고정하면 묻지 않음
 */
class SeasonDatesRefresherTest {

    // 미국 동부 2027-01-15 07:00. 올해(2027) 개막일 전이고 아직 받지 않았다.
    private static final Instant NOW = Instant.parse("2027-01-15T12:00:00Z");
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
    void asksOnlyThisYearOncePerUtcDayBeforeOpeningDay() {
        assertThat(refresher.refresh()).isEqualTo("시즌 날짜 2027");
        assertThat(asked).containsExactly(2027);
        verify(writer).saveSeasonDates(2027, SEASON_2027, NOW);
        // 새 개막일을 바로 쓰도록 지금 시즌이 기억한 값을 버린다.
        verify(currentSeason).forget();

        // 같은 날(UTC) 다시 돌면 묻지 않는다.
        clock.advance(Duration.ofHours(10));
        assertThat(refresher.refresh()).isEmpty();
        assertThat(asked).hasSize(1);

        // 다음 날(UTC): 개막 전에는 MLB가 날짜를 바꿀 수 있어 다시 받는다.
        when(seasonDates.findById(2027)).thenReturn(Optional.of(fetchedAt(2027, NOW.toString())));
        clock.set(Instant.parse("2027-01-16T00:00:00Z"));
        refresher.refresh();
        assertThat(asked).containsExactly(2027, 2027);
    }

    @Test
    void noRequestsDuringTheRegularSeason() {
        // 2026-07-01: 올해(2026) 개막일이 지났고 정규시즌(9/27까지)이 이어진다. 날짜는 오래전에 받았다.
        clock.set(Instant.parse("2026-07-01T12:00:00Z"));
        when(seasonDates.findById(2026)).thenReturn(Optional.of(fetchedAt(2026, "2026-01-02T00:00:00Z")));

        assertThat(refresher.refresh()).isEmpty();
        // 정규시즌 마지막 날(미국 날짜)까지는 묻지 않는다.
        clock.set(Instant.parse("2026-09-27T23:00:00Z"));
        assertThat(refresher.refresh()).isEmpty();
        assertThat(asked).isEmpty();
    }

    @Test
    void asksNextYearOncePerUtcDayAfterTheRegularSeason() {
        // 2026-11-10: 올해 정규시즌이 끝났다. 비시즌 홈의 '개막까지 남은 날'에 쓰려고 내년(2027) 날짜를 받는다.
        clock.set(Instant.parse("2026-11-10T03:00:00Z"));
        when(seasonDates.findById(2026)).thenReturn(Optional.of(fetchedAt(2026, "2026-01-02T00:00:00Z")));

        assertThat(refresher.refresh()).isEqualTo("시즌 날짜 2027");
        verify(writer).saveSeasonDates(eq(2027), eq(SEASON_2027), any());
        // 내년 날짜는 지금 시즌을 바꾸지 못하니 기억한 시즌을 버리지 않는다.
        verify(currentSeason, never()).forget();

        // 같은 날(UTC) 다시 돌면 묻지 않는다.
        clock.set(Instant.parse("2026-11-10T20:00:00Z"));
        assertThat(refresher.refresh()).isEmpty();
        assertThat(asked).containsExactly(2027);

        // 다음 날(UTC): 어제 받은 값이라 다시 받는다(MLB가 날짜를 바꿀 수 있다).
        when(seasonDates.findById(2027)).thenReturn(Optional.of(fetchedAt(2027, "2026-11-10T03:00:00Z")));
        clock.set(Instant.parse("2026-11-11T03:00:00Z"));
        assertThat(refresher.refresh()).isEqualTo("시즌 날짜 2027");
        assertThat(asked).containsExactly(2027, 2027);
    }

    @Test
    void yearFetchedTodayIsNotAskedAfterRestart() {
        // 앱을 다시 띄운 상황: 2027은 오늘(UTC) 이미 받았다.
        when(seasonDates.findById(2027)).thenReturn(Optional.of(fetchedAt(2027, "2027-01-15T00:30:00Z")));

        assertThat(refresher.refresh()).isEmpty();
        assertThat(asked).isEmpty();
    }

    @Test
    void unannouncedYearIsNotSavedAndNotAskedAgainToday() {
        client = client(List.of(SEASON_2026));
        refresher = new SeasonDatesRefresher(client, seasonDates, writer, currentSeason, clock);

        assertThat(refresher.refresh()).isEqualTo("시즌 날짜 발표 전 2027");
        verify(writer, never()).saveSeasonDates(eq(2027), any(), any());

        clock.advance(Duration.ofHours(1));
        assertThat(refresher.refresh()).isEmpty();
        assertThat(asked).containsExactly(2027);
    }

    @Test
    void failureIsAskedAgainNextRun() {
        failYears = Set.of(2027);

        assertThat(refresher.refresh()).isEqualTo("시즌 날짜 실패 2027");
        verify(writer, never()).saveSeasonDates(anyInt(), any(), any());

        // 다음 실행(12분 뒤) 다시 묻는다.
        failYears = Set.of();
        clock.advance(Duration.ofMinutes(12));
        assertThat(refresher.refresh()).isEqualTo("시즌 날짜 2027");
        assertThat(asked).containsExactly(2027, 2027);
    }

    @Test
    void blockedStopsTheRun() {
        blockYears = Set.of(2027);

        assertThatThrownBy(() -> refresher.refresh()).isInstanceOf(MlbBlockedException.class);
        assertThat(asked).containsExactly(2027);
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
