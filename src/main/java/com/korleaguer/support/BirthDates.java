package com.korleaguer.support;

import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

/** balldontlie 생년월일(dob) 읽기와 만 나이 계산 */
public final class BirthDates {

    // 미국식 8/6/1998. 월·일이 한 자리여도 되고, 없는 날짜(2/30 등)는 읽지 않는다.
    private static final DateTimeFormatter US_DATE = DateTimeFormatter.ofPattern("M/d/uuuu")
            .withResolverStyle(ResolverStyle.STRICT);

    private BirthDates() {
    }

    /** yyyy-MM-dd 또는 M/d/yyyy를 읽는다. 비었거나 읽을 수 없으면 null. */
    public static LocalDate parse(String dob) {
        if (dob == null || dob.isBlank()) {
            return null;
        }
        String text = dob.trim();
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException ignored) {
            // 미국식일 수 있으니 아래에서 다시 읽는다.
        }
        try {
            return LocalDate.parse(text, US_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 생년월일이 있으면 today 기준 만 나이. 없거나 today보다 뒤 날짜면 저장된 나이(없으면 null). */
    public static Integer age(LocalDate birthDate, Integer storedAge, LocalDate today) {
        if (birthDate == null || birthDate.isAfter(today)) {
            return storedAge;
        }
        return Period.between(birthDate, today).getYears();
    }
}
