package com.korleaguer.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class KoreanTimeTest {

    @Test
    @DisplayName("세계 표준시를 아홉 시간 빠른 한국 시각으로 바꾼다")
    void utcToKst() {
        // when
        OffsetDateTime kst = KoreanTime.toKst(Instant.parse("2026-09-26T20:05:00Z"));

        // then
        assertThat(kst).isEqualTo(OffsetDateTime.parse("2026-09-27T05:05:00+09:00"));
        assertThat(kst.getOffset()).isEqualTo(ZoneOffset.ofHours(9));
    }

    @Test
    @DisplayName("한국 날짜는 세계 표준시 15시에 다음 날로 바뀐다")
    void kstDateChangesAtFifteenUtc() {
        // when // then
        assertThat(KoreanTime.kstDate(Instant.parse("2026-09-26T14:59:59Z"))).isEqualTo(LocalDate.of(2026, 9, 26));
        assertThat(KoreanTime.kstDate(Instant.parse("2026-09-26T15:00:00Z"))).isEqualTo(LocalDate.of(2026, 9, 27));
        assertThat(KoreanTime.startOfKstDay(LocalDate.of(2026, 9, 27))).isEqualTo(Instant.parse("2026-09-26T15:00:00Z"));
    }

    @Test
    @DisplayName("시각을 적는 꼴이 달라도 같은 시각으로 읽고, 날짜만 있으면 세계 표준시 0시로 본다")
    void parseUtcDateFormats() {
        // given
        Instant expected = Instant.parse("2026-09-24T01:45:00Z");

        // when // then
        assertThat(KoreanTime.parseUtc("2026-09-24T01:45:00.000Z")).isEqualTo(expected);
        assertThat(KoreanTime.parseUtc("2026-09-24T01:45:00+00:00")).isEqualTo(expected);
        assertThat(KoreanTime.parseUtc("2026-09-24T01:45:00+0000")).isEqualTo(expected);
        assertThat(KoreanTime.parseUtc("2026-09-24T10:45:00+09:00")).isEqualTo(expected);
        // 시간대 표시가 없으면 UTC, 날짜만 있으면 그날 0시(UTC)
        assertThat(KoreanTime.parseUtc("2026-09-24T01:45:00")).isEqualTo(expected);
        assertThat(KoreanTime.parseUtc("2026-09-24")).isEqualTo(Instant.parse("2026-09-24T00:00:00Z"));
    }
}
