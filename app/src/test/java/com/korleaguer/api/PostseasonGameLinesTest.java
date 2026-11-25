package com.korleaguer.api;

import com.korleaguer.client.MlbClient;
import com.korleaguer.client.MlbScheduleGame;
import com.korleaguer.client.MlbTeam;
import com.korleaguer.client.SampleMlbClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static com.korleaguer.client.MlbFixtures.batting;
import static com.korleaguer.client.MlbFixtures.boxscore;
import static com.korleaguer.client.MlbFixtures.game;
import static com.korleaguer.client.MlbFixtures.hitter;
import static com.korleaguer.client.MlbFixtures.hittingStat;
import static com.korleaguer.client.MlbFixtures.team;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 경기 줄의 경기 종류(seasonType, 이슈 #190). 정규시즌 경기는 REGULAR, 포스트시즌 경기는 POSTSEASON이고, 뛴 경기와 결장 줄이
 * 모두 그 경기의 종류를 따른다. 웹이 타격 흐름에서 포스트시즌을 빼고(그전에는 정규시즌 경기 수만큼 앞에서 자르기) 달력에
 * 포스트시즌을 표시할 때 쓴다.
 * 김하성(ATL) 한 명: 정규시즌 9/25 출전(7001)·9/26 결장(7002), 디비전 시리즈 10/4 출전(7201)·10/5 결장(7202).
 * 포스트시즌 출전은 gameLog에 없어 박스스코어로 받는다(시즌 백필).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:postseason-lines-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "roster.baseball[0].name-ko=김하성",
        "roster.baseball[0].mlb-id=673490"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PostseasonGameLinesTest {

    @Autowired
    private MockMvc mockMvc;

    @TestConfiguration
    static class Scenario {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-10-20T00:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        @Primary
        MlbClient postseasonClient() {
            MlbTeam atl = team(144, "Atlanta Braves");
            MlbTeam nym = team(121, "New York Mets");
            MlbScheduleGame regularPlayed = game(7001, nym, atl, "2026-09-25T23:10:00Z", "R");
            List<MlbScheduleGame> games = List.of(
                    regularPlayed,
                    game(7002, nym, atl, "2026-09-26T23:10:00Z", "R"),
                    game(7201, nym, atl, "2026-10-04T22:08:00Z", "D"),
                    game(7202, nym, atl, "2026-10-05T22:08:00Z", "D"));
            return new SampleMlbClient(SampleMlbClient.Data.empty()
                    .withPeople(List.of(hitter(673490, "Ha-Seong Kim", atl)))
                    .withSchedule(games)
                    .withHittingLogs(List.of(batting(673490, atl, regularPlayed, 4, 1)))
                    // 7202는 박스스코어가 없다(우리 선수 기록 없음 = 결장)
                    .withBoxscores(Map.of(7201L, boxscore(nym, atl, true, 673490, "300", hittingStat(4, 2), null,
                            List.of(1L), 2, 4))));
        }
    }

    @Test
    @DisplayName("뛴 경기와 결장 모두 정규시즌인지 포스트시즌인지 표시된다")
    void regularAndPostseasonLinesSayWhichKindOfGame() throws Exception {
        // when // then
        mockMvc.perform(get("/api/players/1"))
                .andExpect(jsonPath("$.games[*].gameId").value(contains(7202, 7201, 7002, 7001)))
                .andExpect(jsonPath("$.games[*].played").value(contains(false, true, false, true)))
                .andExpect(jsonPath("$.games[*].seasonType")
                        .value(contains("POSTSEASON", "POSTSEASON", "REGULAR", "REGULAR")))
                // 시즌 성적은 지금처럼 정규시즌만(웹이 지금 이 경기 수만큼 앞에서 잘라 포스트시즌을 뺀다)
                .andExpect(jsonPath("$.hitting.games").value(1));
    }
}
