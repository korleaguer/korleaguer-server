package com.korleaguer.support;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** MLB 경기 상태 → 웹이 쓰는 소문자 값 */
class GameStatusesTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "NULL", textBlock = """
            Final   | F    | Final                | final
            Final   | O    | Game Over            | final
            Final   | F    | Completed Early      | final
            Final   | D    | Postponed            | postponed
            Final   | C    | Cancelled            | canceled
            Live    | I    | In Progress          | in_progress
            Live    | M    | Manager challenge    | in_progress
            Live    | I    | Warmup               | scheduled
            Live    | I    | Delayed: Rain        | delayed
            Live    | U    | Suspended: Rain      | suspended
            Preview | S    | Scheduled            | scheduled
            Preview | P    | Pre-Game             | scheduled
            Preview | P    | Delayed Start: Rain  | delayed
            NULL    | F    | NULL                 | final
            NULL    | NULL | Something New        | something_new
            NULL    | NULL | NULL                 | unknown
            """)
    void mapsMlbStatus(String abstractState, String codedState, String detailedState, String expected) {
        assertThat(GameStatuses.of(abstractState, codedState, detailedState)).isEqualTo(expected);
    }
}
