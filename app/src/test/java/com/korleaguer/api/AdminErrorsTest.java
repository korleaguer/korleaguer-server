package com.korleaguer.api;

import com.korleaguer.ingest.IngestAlreadyRunningException;
import com.korleaguer.ingest.IngestService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리용 수집 엔드포인트의 오류 응답 모양. 토큰이 없으면 403 {"error":"forbidden"},
 * 수집이 이미 도는 중이면 409 {"error":"ingest already running"}(이슈 #243에서 수집 모듈로 옮겨도 그대로여야 한다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminErrorsTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IngestService ingestService;

    @Test
    @DisplayName("관리 토큰 없이 수집을 부르면 403과 forbidden을 준다")
    void withoutTokenIsForbidden() throws Exception {
        // when // then
        mockMvc.perform(post("/admin/ingest/catch-up"))
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"error\":\"forbidden\"}", true));
    }

    @Test
    @DisplayName("이미 수집하는 중에 또 부르면 409와 수집 중이라는 오류를 준다")
    void ingestAlreadyRunningIsConflict() throws Exception {
        // given
        when(ingestService.ingestGames(any())).thenThrow(new IngestAlreadyRunningException());

        // when // then
        mockMvc.perform(post("/admin/ingest/games").param("date", "2026-09-26")
                        .header("X-Admin-Token", "test-admin-token"))
                .andExpect(status().isConflict())
                .andExpect(content().json("{\"error\":\"ingest already running\"}", true));
    }
}
