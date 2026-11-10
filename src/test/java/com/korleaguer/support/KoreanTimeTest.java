package com.korleaguer.support;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class KoreanTimeTest {

    @Test
    void utcToKst() {
        OffsetDateTime kst = KoreanTime.toKst(Instant.parse("2026-09-26T20:05:00Z"));

        assertThat(kst).isEqualTo(OffsetDateTime.parse("2026-09-27T05:05:00+09:00"));
        assertThat(kst.getOffset()).isEqualTo(ZoneOffset.ofHours(9));
    }

    @Test
    void kstDateChangesAtFifteenUtc() {
        assertThat(KoreanTime.kstDate(Instant.parse("2026-09-26T14:59:59Z"))).isEqualTo(LocalDate.of(2026, 9, 26));
        assertThat(KoreanTime.kstDate(Instant.parse("2026-09-26T15:00:00Z"))).isEqualTo(LocalDate.of(2026, 9, 27));
        assertThat(KoreanTime.startOfKstDay(LocalDate.of(2026, 9, 27))).isEqualTo(Instant.parse("2026-09-26T15:00:00Z"));
    }

    @Test
    void parseUtcDateFormats() {
        Instant expected = Instant.parse("2026-09-24T01:45:00Z");

        assertThat(KoreanTime.parseUtc("2026-09-24T01:45:00.000Z")).isEqualTo(expected);
        assertThat(KoreanTime.parseUtc("2026-09-24T01:45:00+00:00")).isEqualTo(expected);
        assertThat(KoreanTime.parseUtc("2026-09-24T01:45:00+0000")).isEqualTo(expected);
        assertThat(KoreanTime.parseUtc("2026-09-24T10:45:00+09:00")).isEqualTo(expected);
        // 시간대 표시가 없으면 UTC, 날짜만 있으면 그날 0시(UTC)
        assertThat(KoreanTime.parseUtc("2026-09-24T01:45:00")).isEqualTo(expected);
        assertThat(KoreanTime.parseUtc("2026-09-24")).isEqualTo(Instant.parse("2026-09-24T00:00:00Z"));
    }
}
