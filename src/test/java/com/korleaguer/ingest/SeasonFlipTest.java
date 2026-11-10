package com.korleaguer.ingest;

import com.korleaguer.api.BaseballQueryService;
import com.korleaguer.config.CurrentSeason;
import com.korleaguer.domain.MinorSeasonRepository;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerRepository;
import com.korleaguer.domain.PlayerSeasonRepository;
import com.korleaguer.domain.SeasonDatesRepository;
import com.korleaguer.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시즌을 고정하지 않으면(korleaguer.season 비움) 앱을 다시 띄우지 않아도 개막일에 새 시즌으로 넘어가고,
 * 다음 따라잡기가 새 시즌 백필과 마이너 경기 수를 이어 받는지 본다(이슈 #31).
 * 샘플 데이터의 시즌 날짜: 2026 개막 3/25, 2027 개막 3/25(미국 날짜). 고우석(마이너 경기 수가 있는 선수) 한 명.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:season-flip-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "korleaguer.season=",
        "roster.baseball[0].name-ko=고우석",
        "roster.baseball[0].mlb-id=808970"
})
@ActiveProfiles("test")
class SeasonFlipTest {

    // 2026-11-10 12:00 KST(미국 날짜 11/9): 비시즌이지만 2026 시즌이다.
    private static final Instant OFF_SEASON = Instant.parse("2026-11-10T03:00:00Z");
    // 2027-03-24 12:00 미국 동부: 개막 하루 전
    private static final Instant DAY_BEFORE_OPENING = Instant.parse("2027-03-24T16:00:00Z");
    // 2027-03-25 12:00 미국 동부: 개막일
    private static final Instant OPENING_DAY = Instant.parse("2027-03-25T16:00:00Z");

    @Autowired
    private IngestService ingestService;

    @Autowired
    private CurrentSeason currentSeason;

    @Autowired
    private BaseballQueryService queries;

    @Autowired
    private MutableClock clock;

    @Autowired
    private PlayerRepository players;

    @Autowired
    private PlayerSeasonRepository playerSeasons;

    @Autowired
    private MinorSeasonRepository minorSeasons;

    @Autowired
    private SeasonDatesRepository seasonDates;

    @TestConfiguration
    static class Scenario {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(OFF_SEASON);
        }
    }

    @Test
    void movesToTheNewSeasonOnOpeningDayWithoutRestart() {
        assertThat(currentSeason.isFixed()).isFalse();

        // 비시즌: 시즌 날짜를 받고 2026 시즌을 받는다.
        ingestService.catchUp(false, false);
        Player go = players.findByMlbIdIsNotNull().getFirst();
        assertThat(seasonDates.findById(2027)).get()
                .extracting(d -> d.getRegularSeasonStart()).isEqualTo(LocalDate.of(2027, 3, 25));
        assertThat(currentSeason.current()).isEqualTo(2026);
        assertThat(playerSeasons.exists(go.getId(), 2026)).isTrue();
        assertThat(minorSeasons.find(go.getId(), 2026)).isPresent();
        assertThat(queries.home().season()).isEqualTo(2026);

        // 해가 바뀌어도 개막 전이면 아직 2026 시즌이다.
        clock.set(DAY_BEFORE_OPENING);
        assertThat(currentSeason.current()).isEqualTo(2026);
        ingestService.catchUp(false, false);
        assertThat(playerSeasons.exists(go.getId(), 2027)).isFalse();

        // 개막일: 다시 띄우지 않아도 2027이 되고, 다음 따라잡기가 2027 시즌과 마이너 경기 수를 받는다.
        clock.set(OPENING_DAY);
        assertThat(currentSeason.current()).isEqualTo(2027);
        ingestService.catchUp(false, false);
        assertThat(playerSeasons.exists(go.getId(), 2027)).isTrue();
        assertThat(minorSeasons.find(go.getId(), 2027)).isPresent();
        assertThat(queries.home().season()).isEqualTo(2027);
    }
}
