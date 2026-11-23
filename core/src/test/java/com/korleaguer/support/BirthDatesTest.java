package com.korleaguer.support;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class BirthDatesTest {

    @Test
    void parsesIsoAndUsFormats() {
        assertThat(BirthDates.parse("1998-08-20")).isEqualTo(LocalDate.of(1998, 8, 20));
        assertThat(BirthDates.parse("8/6/1998")).isEqualTo(LocalDate.of(1998, 8, 6));
        assertThat(BirthDates.parse("08/06/1998")).isEqualTo(LocalDate.of(1998, 8, 6));
        assertThat(BirthDates.parse("12/31/1998")).isEqualTo(LocalDate.of(1998, 12, 31));
        assertThat(BirthDates.parse(" 1998-08-20 ")).isEqualTo(LocalDate.of(1998, 8, 20));
    }

    @Test
    void unreadableIsNull() {
        assertThat(BirthDates.parse(null)).isNull();
        assertThat(BirthDates.parse("")).isNull();
        assertThat(BirthDates.parse("  ")).isNull();
        assertThat(BirthDates.parse("garbage")).isNull();
        assertThat(BirthDates.parse("1998/08/20")).isNull();
        assertThat(BirthDates.parse("20-08-1998")).isNull();
        assertThat(BirthDates.parse("2/30/1998")).isNull();
        assertThat(BirthDates.parse("13/1/1998")).isNull(); // 월이 먼저인 미국식이라 13월은 없다.
    }

    @Test
    void ageFromBirthDateChangesOnTheBirthday() {
        LocalDate birthDate = LocalDate.of(1998, 8, 20);

        assertThat(BirthDates.age(birthDate, null, LocalDate.of(2026, 8, 19))).isEqualTo(27);
        assertThat(BirthDates.age(birthDate, null, LocalDate.of(2026, 8, 20))).isEqualTo(28);
        assertThat(BirthDates.age(birthDate, null, LocalDate.of(2026, 11, 2))).isEqualTo(28);
    }

    @Test
    void birthDateWinsOverStoredAge() {
        assertThat(BirthDates.age(LocalDate.of(1998, 8, 20), 30, LocalDate.of(2026, 11, 2))).isEqualTo(28);
    }

    @Test
    void leapDayBirthday() {
        LocalDate birthDate = LocalDate.of(2000, 2, 29);

        assertThat(BirthDates.age(birthDate, null, LocalDate.of(2026, 2, 28))).isEqualTo(25);
        assertThat(BirthDates.age(birthDate, null, LocalDate.of(2026, 3, 1))).isEqualTo(26);
    }

    @Test
    void withoutBirthDateTheStoredAgeIsUsed() {
        assertThat(BirthDates.age(null, 30, LocalDate.of(2026, 11, 2))).isEqualTo(30);
        assertThat(BirthDates.age(null, null, LocalDate.of(2026, 11, 2))).isNull();
        // 미래 날짜는 믿지 않는다.
        assertThat(BirthDates.age(LocalDate.of(2030, 1, 1), 30, LocalDate.of(2026, 11, 2))).isEqualTo(30);
    }
}
