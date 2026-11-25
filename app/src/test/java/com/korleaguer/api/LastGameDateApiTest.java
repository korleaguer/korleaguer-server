package com.korleaguer.api;

import com.korleaguer.client.MlbClient;
import com.korleaguer.client.MlbMinorGames;
import com.korleaguer.client.MlbPerson;
import com.korleaguer.client.MlbScheduleGame;
import com.korleaguer.client.MlbTeam;
import com.korleaguer.client.MlbTransaction;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static com.korleaguer.client.MlbFixtures.batting;
import static com.korleaguer.client.MlbFixtures.game;
import static com.korleaguer.client.MlbFixtures.hitter;
import static com.korleaguer.client.MlbFixtures.team;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 내 선수 카드의 마지막 경기 날짜(status.lastGameDate)는 그 경기 시작 시각의 한국 날짜다(이슈 #183). 선수 화면 경기 달력
 * (웹은 startTimeKst의 날짜로 칸을 정한다)과 같은 경기가 같은 날짜가 되는지 본다. 2026 비시즌(한국 11월 11일).
 * <ul>
 *   <li>가선수(FA): 마지막 경기가 미국 9월 27일 밤 경기 → 한국 9월 28일(예전에는 미국 날짜 9월 27일)</li>
 *   <li>나선수(국내 복귀): 한국에서 연 경기(한국 9월 27일 저녁 = 미국 9월 27일 아침) → 두 날짜가 같아 그대로 9월 27일</li>
 *   <li>다선수(시즌 끝): 미국 9월 26일 밤 경기 → 한국 9월 27일</li>
 *   <li>라선수(FA, 마지막은 마이너): 미국 9월 20일 저녁 트리플A 경기(샘플 일정 19:05 동부) → 한국 9월 21일</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:last-game-date;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "roster.baseball[0].name-ko=가선수",
        "roster.baseball[0].mlb-id=900001",
        "roster.baseball[1].name-ko=나선수",
        "roster.baseball[1].mlb-id=900002",
        "roster.baseball[1].returned-ko=LG 트윈스",
        "roster.baseball[2].name-ko=다선수",
        "roster.baseball[2].mlb-id=900003",
        "roster.baseball[3].name-ko=라선수",
        "roster.baseball[3].mlb-id=900004"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LastGameDateApiTest {

    private static final MlbTeam SF = team(137, "San Francisco Giants");
    private static final MlbTeam COL = team(115, "Colorado Rockies");
    private static final MlbTeam MIN = team(142, "Minnesota Twins");
    private static final MlbTeam LAD = team(119, "Los Angeles Dodgers");
    private static final MlbTeam MIL = team(158, "Milwaukee Brewers");
    private static final MlbTeam CHC = team(112, "Chicago Cubs");

    @Autowired
    private MockMvc mockMvc;

    private final JsonMapper json = JsonMapper.builder().build();

    @TestConfiguration
    static class Scenario {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-11-11T12:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        @Primary
        MlbClient lastGameDateClient() {
            MlbTeam nashville = new MlbTeam(556L, "Nashville Sounds", null, new MlbTeam.Sport(11L, "Triple-A"), 158L);
            List<MlbScheduleGame> games = List.of(
                    game(9001, COL, SF, "2026-09-28T01:10:00Z", "R"),   // 가선수: 미국 9/27 21:10, 한국 9/28 10:10
                    game(9002, LAD, MIN, "2026-09-27T10:05:00Z", "R"),  // 나선수: 미국 9/27 06:05, 한국 9/27 19:05
                    game(9003, SF, COL, "2026-09-27T02:10:00Z", "R"),   // 다선수: 미국 9/26 22:10, 한국 9/27 11:10
                    game(9004, CHC, MIL, "2026-08-01T23:00:00Z", "R")); // 라선수: 그 뒤 마이너로
            MlbPerson ga = hitter(900001, "Ga Player", SF);
            MlbPerson ra = hitter(900004, "Ra Player", nashville);
            return new SampleMlbClient(SampleMlbClient.Data.empty()
                    .withPeople(List.of(ga, hitter(900002, "Na Player", MIN), hitter(900003, "Da Player", COL), ra))
                    .withTeams(List.of(nashville))
                    .withTransactions(List.of(
                            new MlbTransaction(1L, ga, "2026-11-02", "2026-11-02", "DFA", "Declared Free Agency",
                                    "RF Ga Player elected free agency.", null, SF),
                            new MlbTransaction(2L, ra, "2026-08-19", "2026-08-19", "OUT", "Outrighted",
                                    "Milwaukee Brewers sent CF Ra Player outright to Nashville Sounds.", MIL, nashville),
                            new MlbTransaction(3L, ra, "2026-11-02", "2026-11-02", "DFA", "Declared Free Agency",
                                    "CF Ra Player elected free agency.", null, nashville)))
                    .withSchedule(games)
                    .withHittingLogs(List.of(
                            batting(900001, SF, games.get(0), 4, 1),
                            batting(900002, MIN, games.get(1), 3, 1),
                            batting(900003, COL, games.get(2), 4, 2),
                            batting(900004, MIL, games.get(3), 3, 1)))
                    // 라선수의 트리플A 경기 5개(샘플이 9월 16~20일 미국 저녁 경기로 지어낸다)
                    .withMinorGames(Map.of(900004L, new MlbMinorGames(5, 0))));
        }
    }

    @Test
    @DisplayName("내 선수 카드의 마지막 경기 날짜는 경기 시작 시각의 한국 날짜다")
    void lastGameDateIsTheKoreanDateOfTheStartTime() throws Exception {
        // when
        JsonNode my = get("/api/my?ids=1,2,3,4&days=7");

        // then
        JsonNode ga = my.get("players").get(0).get("status");
        JsonNode na = my.get("players").get(1).get("status");
        JsonNode da = my.get("players").get(2).get("status");
        JsonNode ra = my.get("players").get(3).get("status");

        // 미국 9월 27일 밤 경기 = 한국 9월 28일 오전
        assertThat(ga.get("kind").asString()).isEqualTo("FA");
        assertThat(ga.get("lastGameDate").asString()).isEqualTo("2026-09-28");
        assertThat(ga.get("lastGameLevel").asString()).isEqualTo("MLB");
        // 한국에서도 같은 날인 경기는 그대로
        assertThat(na.get("kind").asString()).isEqualTo("KBO");
        assertThat(na.get("lastGameDate").asString()).isEqualTo("2026-09-27");
        // 시즌 끝도 같은 기준(한국 날짜)
        assertThat(da.get("kind").asString()).isEqualTo("SEASON_OVER");
        assertThat(da.get("lastGameDate").asString()).isEqualTo("2026-09-27");
        // 마지막이 마이너 경기면 그 경기 일정의 시작 시각(미국 9월 20일 저녁 = 한국 9월 21일 아침)
        assertThat(ra.get("kind").asString()).isEqualTo("FA");
        assertThat(ra.get("lastGameDate").asString()).isEqualTo("2026-09-21");
        assertThat(ra.get("lastGameLevel").asString()).isEqualTo("AAA");
        // FA 며칠째는 FA 선언(이동 기록, 미국 11월 2일)부터 오늘(미국 11월 11일)까지 그대로
        assertThat(ga.get("days").asInt()).isEqualTo(10);
    }

    @Test
    @DisplayName("마지막 경기 날짜가 경기 달력에서 그 경기가 놓인 날과 같다")
    void lastGameDateIsTheDayTheCalendarShowsThatGame() throws Exception {
        // when
        // 선수 화면 경기 달력은 경기 줄 startTimeKst의 날짜로 칸을 정한다(+09:00 시각이라 앞 10글자)
        JsonNode my = get("/api/my?ids=1,4&days=7");
        String gaCalendar = get("/api/players/1").get("games").get(0).get("startTimeKst").asString();
        String raCalendar = get("/api/players/4").get("minorGames").get(0).get("startTimeKst").asString();

        // then
        assertThat(gaCalendar).startsWith(my.get("players").get(0).get("status").get("lastGameDate").asString());
        assertThat(raCalendar).startsWith(my.get("players").get(1).get("status").get("lastGameDate").asString());
    }

    private JsonNode get(String path) throws Exception {
        String body = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(body);
    }
}
