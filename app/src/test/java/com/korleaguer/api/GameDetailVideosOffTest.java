package com.korleaguer.api;

import com.korleaguer.client.MlbClient;
import com.korleaguer.client.MlbGameContent;
import com.korleaguer.client.MlbPlayByPlay;
import com.korleaguer.client.SampleMlbClient;
import com.korleaguer.ingest.GameDetailIngest;
import com.korleaguer.video.PlayVideoFinder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 영상 스위치를 끄면(KORLEAGUER_VIDEOS=false) 하이라이트를 비우고 공 영상도 묻지 않는다. 타석 기록은 그대로(저장된 상세에 읽을 때 적용) */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:game-detail-off-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "korleaguer.videos.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GameDetailVideosOffTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GameDetailIngest gameDetailIngest;

    @Test
    @DisplayName("영상을 끄면 타석 기록은 남고 하이라이트와 공 영상은 나오지 않는다")
    void noVideosWhenSwitchedOff() throws Exception {
        // given
        // 수집은 스위치와 상관없이 받아 두고(다시 켜면 바로 보이게), 읽을 때 비운다
        gameDetailIngest.fill(1000);

        // when // then
        String body = mockMvc.perform(get("/api/players/1/games/1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plays", hasSize(4)))
                .andExpect(jsonPath("$.clips").value(false))
                .andExpect(jsonPath("$.highlights", hasSize(0)))
                .andReturn().getResponse().getContentAsString();
        // 상세에 있는 공이라 켜져 있으면 영상을 준다
        String playId = Scenario.JSON.readTree(body).at("/plays/0/playId").asString();
        assertThat(playId).isNotBlank();
        mockMvc.perform(get("/api/players/1/games/1001/clips/" + playId))
                .andExpect(status().isNotFound());
    }

    @TestConfiguration
    static class Scenario {

        private static final JsonMapper JSON = JsonMapper.builder().build();

        @Bean
        @Primary
        MlbClient detailClient(JsonMapper jsonMapper) {
            return new SampleMlbClient(jsonMapper) {
                @Override
                public MlbPlayByPlay getPlayByPlay(long gamePk) {
                    return read("playbyplay-823898.json", MlbPlayByPlay.class);
                }

                @Override
                public MlbGameContent getGameContent(long gamePk) {
                    return read("content-823898.json", MlbGameContent.class);
                }
            };
        }

        @Bean
        @Primary
        PlayVideoFinder alwaysFound() {
            return playId -> new PlayVideoFinder.Lookup(true, Optional.of("https://clips.example/1.mp4"));
        }

        private static <T> T read(String name, Class<T> type) {
            try (InputStream in = new ClassPathResource("mlb/" + name).getInputStream()) {
                return JSON.readValue(in, type);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
