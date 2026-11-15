package com.korleaguer.api;

import com.korleaguer.client.MlbClient;
import com.korleaguer.client.MlbGameContent;
import com.korleaguer.client.MlbPlayByPlay;
import com.korleaguer.client.SampleMlbClient;
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

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 영상 스위치를 끄면(KORLEAGUER_VIDEOS=false) 하이라이트를 비우고 공 영상도 묻지 않는다. 타석 기록은 그대로 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:game-detail-off-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "korleaguer.videos.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GameDetailVideosOffTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void noVideosWhenSwitchedOff() throws Exception {
        mockMvc.perform(get("/api/players/1/games/1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plays", hasSize(4)))
                .andExpect(jsonPath("$.clips").value(false))
                .andExpect(jsonPath("$.highlights", hasSize(0)));
        mockMvc.perform(get("/api/players/1/games/1001/clips/0b0e2a3c-1111-2222-3333-444455556666"))
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

        private static <T> T read(String name, Class<T> type) {
            try (InputStream in = new ClassPathResource("mlb/" + name).getInputStream()) {
                return JSON.readValue(in, type);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
