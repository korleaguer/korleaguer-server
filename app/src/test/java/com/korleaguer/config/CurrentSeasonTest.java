package com.korleaguer.config;

import com.korleaguer.domain.SeasonDates;
import com.korleaguer.domain.SeasonDatesRepository;
import com.korleaguer.support.MutableClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 지금 시즌 정하기: 고정값, 정규시즌 개막일(미국 날짜) 전후, 개막일을 모를 때 3월 20일, 몇 분 기억하기 */
class CurrentSeasonTest {

    private static final KorleaguerProperties AUTO = properties(null);

    private final SeasonDatesRepository seasonDates = mock(SeasonDatesRepository.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-10T03:00:00Z"));

    @Test
    @DisplayName("시즌을 2025로 정해 두면 저장된 개막일을 보지 않고 2025 시즌이다")
    void fixedSeasonWins() {
        // given
        CurrentSeason season = new CurrentSeason(properties(2025), DataMode.MLB, seasonDates, clock);

        // when // then
        assertThat(season.current()).isEqualTo(2025);
        assertThat(season.isFixed()).isTrue();
        verifyNoInteractions(seasonDates);
    }

    @Test
    @DisplayName("시즌 설정이 비었거나 없으면 값이 없고 2027이라 적으면 2027로 읽는다")
    void blankSettingMeansAutomatic() {
        // when // then
        // application.properties의 korleaguer.season=${KORLEAGUER_SEASON:}가 비어 있을 때와 같다.
        assertThat(bind(Map.of("korleaguer.season", "", "korleaguer.data-source", "sample")).season()).isNull();
        assertThat(bind(Map.of("korleaguer.data-source", "sample")).season()).isNull();
        assertThat(bind(Map.of("korleaguer.season", "2027", "korleaguer.data-source", "sample")).season())
                .isEqualTo(2027);
    }

    @Test
    @DisplayName("샘플 모드는 2027년에도 2026 시즌이고 시즌을 정해 두면 그 시즌이다")
    void sampleModeStaysOnTheSampleSeason() {
        // given
        // 샘플 데이터에는 2026 경기만 있다. 날짜가 지나도 샘플 화면이 비지 않게 2026으로 고정한다.
        clock.set(Instant.parse("2027-06-01T12:00:00Z"));
        CurrentSeason season = new CurrentSeason(AUTO, DataMode.SAMPLE, seasonDates, clock);

        // when // then
        assertThat(season.current()).isEqualTo(2026);
        assertThat(season.seasonAt(Instant.parse("2027-05-01T12:00:00Z"))).isEqualTo(2026);
        assertThat(season.isFixed()).isTrue();
        verifyNoInteractions(seasonDates);
        // korleaguer.season을 정했으면 샘플 모드여도 그 값이다.
        assertThat(new CurrentSeason(properties(2025), DataMode.SAMPLE, seasonDates, clock).current()).isEqualTo(2025);
    }

    @Test
    @DisplayName("지난 날짜는 그때의 시즌으로, 아직 오지 않은 날짜는 지금 시즌으로 본다")
    void seasonAtFollowsTheWindowsOwnDate() {
        // given
        stored(2026, "2026-03-25");
        stored(2027, "2027-03-25");
        CurrentSeason season = new CurrentSeason(AUTO, DataMode.MLB, seasonDates, clock);

        // 개막 이틀 뒤(2027). 마지막으로 뛴 기간이 지난 9월이면 그 기간은 2026 시즌이다.
        clock.set(Instant.parse("2027-03-27T16:00:00Z"));

        // when // then
        assertThat(season.seasonAt(Instant.parse("2026-09-27T23:00:00Z"))).isEqualTo(2026);
        assertThat(season.seasonAt(Instant.parse("2027-03-26T16:00:00Z"))).isEqualTo(2027);

        // given
        // 개막 전날 미국 저녁(한국은 개막일 아침): '최근 N일' 끝(한국 내일 0시)은 아직 오지 않았으니 지금 시즌(2026)이다.
        clock.set(Instant.parse("2027-03-24T23:00:00Z"));

        // when // then
        assertThat(season.current()).isEqualTo(2026);
        assertThat(season.seasonAt(Instant.parse("2027-03-25T14:59:59Z"))).isEqualTo(2026);
    }

    @Test
    @DisplayName("2027 개막일 미국 동부 0시 전까지는 2026, 그때부터 2027 시즌이다")
    void stays2026UntilOpeningDay2027() {
        // given
        stored(2026, "2026-03-25");
        stored(2027, "2027-03-25");
        CurrentSeason season = new CurrentSeason(AUTO, DataMode.MLB, seasonDates, clock);

        // when // then
        assertThat(season.isFixed()).isFalse();

        // 2026-11-10: 올해 개막일이 지났다.
        assertThat(season.current()).isEqualTo(2026);

        // given
        // 해가 바뀌어도 개막 전이면 작년 시즌이다.
        clock.set(Instant.parse("2027-01-15T12:00:00Z"));

        // when // then
        assertThat(season.current()).isEqualTo(2026);

        // given
        // 미국 동부 3/24 23:59(한국은 이미 3/25 낮)까지는 2026
        clock.set(Instant.parse("2027-03-25T03:59:59Z"));

        // when // then
        assertThat(season.current()).isEqualTo(2026);

        // given
        // 미국 동부 3/25 00:00부터 2027
        clock.set(Instant.parse("2027-03-25T04:00:00Z"));

        // when // then
        assertThat(season.current()).isEqualTo(2027);
    }

    @Test
    @DisplayName("저장된 개막일이 3월 20일보다 이르면 개막일부터 새 시즌이다")
    void usesStoredOpeningDayNotTheFallback() {
        // given
        // 개막일이 3월 20일보다 이르면(해외 개막전 등) 그날부터 새 시즌이다.
        stored(2027, "2027-03-18");
        CurrentSeason season = new CurrentSeason(AUTO, DataMode.MLB, seasonDates, clock);

        clock.set(Instant.parse("2027-03-18T16:00:00Z"));

        // when // then
        assertThat(season.current()).isEqualTo(2027);
    }

    @Test
    @DisplayName("개막일을 모르면 미국 날짜 3월 20일부터 새 시즌으로 본다")
    void fallsBackToMarch20WhenOpeningDayIsUnknown() {
        // given
        when(seasonDates.findById(anyInt())).thenReturn(Optional.empty());
        CurrentSeason season = new CurrentSeason(AUTO, DataMode.MLB, seasonDates, clock);

        clock.set(Instant.parse("2027-03-20T03:59:59Z")); // 미국 동부 3/19

        // when // then
        assertThat(season.current()).isEqualTo(2026);

        // given
        clock.set(Instant.parse("2027-03-20T04:00:00Z")); // 미국 동부 3/20

        // when // then
        assertThat(season.current()).isEqualTo(2027);

        assertThat(CurrentSeason.decide(LocalDate.of(2026, 11, 10), null)).isEqualTo(2026);
        assertThat(CurrentSeason.decide(LocalDate.of(2027, 1, 1), null)).isEqualTo(2026);
    }

    @Test
    @DisplayName("지금 시즌을 5분 기억하고, 5분이 지나거나 기억을 지우면 DB에서 다시 읽는다")
    void remembersForAFewMinutesThenAsksTheDbAgain() {
        // given
        stored(2026, "2026-03-25");
        CurrentSeason season = new CurrentSeason(AUTO, DataMode.MLB, seasonDates, clock);

        // when
        season.current();
        clock.advance(Duration.ofMinutes(4));
        season.current();

        // then
        verify(seasonDates, times(1)).findById(2026);

        // given
        // 기억한 지 5분이 지나면 다시 읽는다.
        clock.advance(Duration.ofMinutes(1));

        // when
        season.current();

        // then
        verify(seasonDates, times(2)).findById(2026);

        // when
        // 수집기가 새 날짜를 받으면(forget) 바로 다시 읽는다.
        season.forget();
        season.current();

        // then
        verify(seasonDates, times(3)).findById(2026);
    }

    private void stored(int year, String openingDay) {
        SeasonDates dates = new SeasonDates(year);
        dates.update(LocalDate.parse(openingDay), LocalDate.of(year, 9, 27), null, Instant.EPOCH);
        when(seasonDates.findById(year)).thenReturn(Optional.of(dates));
    }

    private static KorleaguerProperties properties(Integer season) {
        return new KorleaguerProperties(season, null, null, null, null, null);
    }

    private static KorleaguerProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bind("korleaguer", KorleaguerProperties.class)
                .get();
    }
}
