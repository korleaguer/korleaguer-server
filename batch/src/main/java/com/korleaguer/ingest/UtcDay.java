package com.korleaguer.ingest;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/** 수집이 '하루 한 번'을 셀 때의 하루. UTC 날짜로 끊는다(오늘 이미 받았는지, 오늘 이미 물어봤는지) */
final class UtcDay {

    private UtcDay() {
    }

    /** 그 시각의 UTC 날짜 */
    static LocalDate of(Instant instant) {
        return LocalDate.ofInstant(instant, ZoneOffset.UTC);
    }

    /** 그 UTC 날짜가 시작하는 시각. 이 시각부터 받은 것이 '그날 받은 것'이다 */
    static Instant start(LocalDate day) {
        return day.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** 그 시각이 든 UTC 날짜가 시작하는 시각 */
    static Instant startOf(Instant instant) {
        return start(of(instant));
    }
}
