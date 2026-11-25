package com.korleaguer.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class BirthDatesTest {

    @Test
    @DisplayName("생년월일은 연도부터 쓴 꼴과 월부터 쓴 미국식을 모두 읽는다")
    void parsesIsoAndUsFormats() {
        // when // then
        assertThat(BirthDates.parse("1998-08-20")).isEqualTo(LocalDate.of(1998, 8, 20));
        assertThat(BirthDates.parse("8/6/1998")).isEqualTo(LocalDate.of(1998, 8, 6));
        assertThat(BirthDates.parse("08/06/1998")).isEqualTo(LocalDate.of(1998, 8, 6));
        assertThat(BirthDates.parse("12/31/1998")).isEqualTo(LocalDate.of(1998, 12, 31));
        assertThat(BirthDates.parse(" 1998-08-20 ")).isEqualTo(LocalDate.of(1998, 8, 20));
    }

    @Test
    @DisplayName("빈 값이나 없는 날짜, 다른 꼴의 생년월일은 비워 둔다")
    void unreadableIsNull() {
        // when // then
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
    @DisplayName("생년월일로 구한 나이는 생일날에 한 살 오른다")
    void ageFromBirthDateChangesOnTheBirthday() {
        // given
        LocalDate birthDate = LocalDate.of(1998, 8, 20);

        // when // then
        assertThat(BirthDates.age(birthDate, null, LocalDate.of(2026, 8, 19))).isEqualTo(27);
        assertThat(BirthDates.age(birthDate, null, LocalDate.of(2026, 8, 20))).isEqualTo(28);
        assertThat(BirthDates.age(birthDate, null, LocalDate.of(2026, 11, 2))).isEqualTo(28);
    }

    @Test
    @DisplayName("생년월일이 있으면 저장된 나이 대신 생년월일로 나이를 구한다")
    void birthDateWinsOverStoredAge() {
        // when // then
        assertThat(BirthDates.age(LocalDate.of(1998, 8, 20), 30, LocalDate.of(2026, 11, 2))).isEqualTo(28);
    }

    @Test
    @DisplayName("2월 29일생은 평년에 3월 1일이 되면 한 살 오른다")
    void leapDayBirthday() {
        // given
        LocalDate birthDate = LocalDate.of(2000, 2, 29);

        // when // then
        assertThat(BirthDates.age(birthDate, null, LocalDate.of(2026, 2, 28))).isEqualTo(25);
        assertThat(BirthDates.age(birthDate, null, LocalDate.of(2026, 3, 1))).isEqualTo(26);
    }

    @Test
    @DisplayName("생년월일이 없거나 미래 날짜면 저장된 나이를 쓰고, 그것도 없으면 비워 둔다")
    void withoutBirthDateTheStoredAgeIsUsed() {
        // when // then
        assertThat(BirthDates.age(null, 30, LocalDate.of(2026, 11, 2))).isEqualTo(30);
        assertThat(BirthDates.age(null, null, LocalDate.of(2026, 11, 2))).isNull();
        // 미래 날짜는 믿지 않는다.
        assertThat(BirthDates.age(LocalDate.of(2030, 1, 1), 30, LocalDate.of(2026, 11, 2))).isEqualTo(30);
    }
}
