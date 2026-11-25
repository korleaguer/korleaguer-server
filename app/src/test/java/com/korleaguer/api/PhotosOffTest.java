package com.korleaguer.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** korleaguer.images.official-photos=false면 photoUrl은 모두 null이고, MLB 공식 페이지 링크는 그대로 나온다. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:photos-off-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "korleaguer.images.official-photos=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PhotosOffTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("공식 사진을 끄면 홈·선수 목록·선수 화면의 사진 주소가 모두 빈다")
    void noPhotoUrlAnywhere() throws Exception {
        // when // then
        mockMvc.perform(get("/api/home"))
                .andExpect(jsonPath("$.recent.lines[*].photoUrl").value(contains(
                        nullValue(), nullValue(), nullValue(), nullValue(), nullValue(), nullValue())))
                .andExpect(jsonPath("$.hitters[*].photoUrl").value(contains(
                        nullValue(), nullValue(), nullValue(), nullValue(), nullValue())))
                .andExpect(jsonPath("$.pitchers[0].photoUrl").value(nullValue()));
        mockMvc.perform(get("/api/players"))
                .andExpect(jsonPath("$[*].photoUrl").value(contains(
                        nullValue(), nullValue(), nullValue(), nullValue(), nullValue(), nullValue())));
        mockMvc.perform(get("/api/players/1"))
                .andExpect(jsonPath("$.player.photoUrl").value(nullValue()))
                .andExpect(jsonPath("$.hitting.photoUrl").value(nullValue()))
                .andExpect(jsonPath("$.games[0].photoUrl").value(nullValue()));
    }

    @Test
    @DisplayName("공식 사진을 꺼도 MLB 선수 페이지 링크와 국내 경력은 그대로 나온다")
    void mlbLinkDoesNotDependOnPhotoSetting() throws Exception {
        // when // then
        mockMvc.perform(get("/api/players/1"))
                .andExpect(jsonPath("$.player.mlbUrl").value("https://www.mlb.com/player/808982"))
                .andExpect(jsonPath("$.player.careerKo").value("넥센·키움 히어로즈 (2017–2023)"));
    }
}
