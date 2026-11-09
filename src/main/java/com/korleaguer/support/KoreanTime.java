package com.korleaguer.support;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalQueries;

/** UTC로 저장한 시각을 한국 시간으로 바꾸는 도우미 */
public final class KoreanTime {

    public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    // 2026-09-24 / 2026-09-24T01:45:00(.000) 뒤에 Z, +00:00, +0000 이 붙거나 안 붙은 형식
    private static final DateTimeFormatter UTC_DATE = new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE)
            .optionalStart().appendLiteral('T').append(DateTimeFormatter.ISO_LOCAL_TIME).optionalEnd()
            .optionalStart().appendOffset("+HH:MM", "Z").optionalEnd()
            .optionalStart().appendOffset("+HHMM", "Z").optionalEnd()
            .toFormatter();

    private KoreanTime() {
    }

    /** 예: 2026-09-26T20:05:00Z → 2026-09-27T05:05+09:00 */
    public static OffsetDateTime toKst(Instant instant) {
        return instant.atZone(SEOUL).toOffsetDateTime();
    }

    public static LocalDate kstDate(Instant instant) {
        return instant.atZone(SEOUL).toLocalDate();
    }

    /** 한국 날짜의 0시(한국 시간)를 UTC 시각으로 */
    public static Instant startOfKstDay(LocalDate date) {
        return date.atStartOfDay(SEOUL).toInstant();
    }

    /**
     * MLB gameDate 같은 시각 문자열(예: 2026-09-26T20:05:00Z)을 읽는다.
     * 시간대 표시가 없으면 UTC로, 시각이 없으면 그날 0시(UTC)로 간주한다.
     *
     * @throws java.time.format.DateTimeParseException 읽을 수 없는 형식일 때
     */
    public static Instant parseUtc(String text) {
        TemporalAccessor parsed = UTC_DATE.parse(text.trim());
        LocalTime time = parsed.query(TemporalQueries.localTime());
        ZoneOffset offset = parsed.query(TemporalQueries.offset());
        return LocalDateTime.of(LocalDate.from(parsed), time == null ? LocalTime.MIDNIGHT : time)
                .toInstant(offset == null ? ZoneOffset.UTC : offset);
    }
}
