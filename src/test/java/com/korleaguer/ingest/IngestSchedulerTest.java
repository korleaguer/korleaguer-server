package com.korleaguer.ingest;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** 경기 갱신 차례: 한국 시간 01:00~16:00이면 12분마다, 그 밖에는 정각 근처만 */
class IngestSchedulerTest {

    @ParameterizedTest
    @CsvSource(textBlock = """
            2026-09-26T16:00:00Z, true
            2026-09-26T16:36:00Z, true
            2026-09-27T06:48:00Z, true
            2026-09-27T07:00:00Z, true
            2026-09-27T07:12:00Z, false
            2026-09-27T11:36:00Z, false
            2026-09-27T12:00:00Z, true
            2026-09-26T15:48:00Z, false
            """)
    void gameTimeFollowsKoreanHours(String utc, boolean expected) {
        // 16:00Z = 한국 01:00, 07:00Z = 한국 16:00
        assertThat(IngestScheduler.gameTime(Instant.parse(utc))).isEqualTo(expected);
    }
}
