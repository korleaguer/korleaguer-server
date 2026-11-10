package com.korleaguer.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * MLB에 없는 선수 번호(받기 실패): MLB 값(나이·등번호·키 등)은 null이지만
 * 명단 값(photoUrl, mlbUrl, careerKo)은 나온다. 선수 페이지를 열어도 오류 없이 DB에 있는 것만 준다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:unmatched-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "roster.baseball[0].name-ko=모르는선수",
        "roster.baseball[0].mlb-id=111111",
        "roster.baseball[0].career-ko=테스트 소속 (2020–2024)"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UnmatchedPlayerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void playerPageHasRosterValuesOnly() throws Exception {
        mockMvc.perform(get("/api/players/1"))
                .andExpect(jsonPath("$.player.nameKo").value("모르는선수"))
                .andExpect(jsonPath("$.player.nameEn").value(nullValue()))
                .andExpect(jsonPath("$.player.photoUrl").value("https://img.mlbstatic.com/mlb-photos/image/upload/"
                        + "d_people:generic:headshot:silo:current.png/w_360,q_auto:best,f_auto/v1/people/111111/headshot/silo/current"))
                .andExpect(jsonPath("$.player.mlbUrl").value("https://www.mlb.com/player/111111"))
                .andExpect(jsonPath("$.player.careerKo").value("테스트 소속 (2020–2024)"))
                .andExpect(jsonPath("$.player.jersey").value(nullValue()))
                .andExpect(jsonPath("$.player.birthDate").value(nullValue()))
                .andExpect(jsonPath("$.player.age").value(nullValue()))
                .andExpect(jsonPath("$.player.heightCm").value(nullValue()))
                .andExpect(jsonPath("$.player.weightKg").value(nullValue()))
                .andExpect(jsonPath("$.player.batsThrowsKo").value(nullValue()))
                .andExpect(jsonPath("$.player.debutYear").value(nullValue()));
    }

    @Test
    void listHasPhotoButNoAgeOrJersey() throws Exception {
        mockMvc.perform(get("/api/players"))
                .andExpect(jsonPath("$[0].nameKo").value("모르는선수"))
                .andExpect(jsonPath("$[0].photoUrl").exists())
                .andExpect(jsonPath("$[0].age").value(nullValue()))
                .andExpect(jsonPath("$[0].jersey").value(nullValue()));
    }
}
