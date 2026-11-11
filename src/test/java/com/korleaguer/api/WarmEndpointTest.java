package com.korleaguer.api;

import com.korleaguer.config.KorleaguerProperties;
import com.korleaguer.config.ResponseCaches;
import com.korleaguer.posting.PostingProperties;
import com.korleaguer.posting.Postings;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /actuator/warm: 배포 스크립트가 새 API로 요청을 넘기기 전에 기다리는 '미리 채우기를 마쳤나'.
 * 자동 수집이 켜진 운영에서는 첫 채우기가 끝나야 준비되고, 꺼진 곳(테스트·로컬)에서는 늘 준비됐다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WarmEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void readyWhenWarmingIsOff() throws Exception {
        mockMvc.perform(get("/actuator/warm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warm").value(true));
    }

    @Test
    void withScheduledIngestItIsReadyOnlyAfterTheFirstFill() {
        KorleaguerProperties properties = new KorleaguerProperties(2026, null,
                new KorleaguerProperties.Ingest(true, null, null), null, null, null);
        CacheWarmer warmer = new CacheWarmer(mock(BaseballQueryService.class), mock(MyPlayersService.class),
                mock(ResponseCaches.class), new Postings(new PostingProperties(List.of(), List.of())),
                Executors.newVirtualThreadPerTaskExecutor(), Clock.systemUTC(), properties);
        WarmEndpoint endpoint = new WarmEndpoint(warmer);

        assertThat(warmer.ready()).isFalse();
        assertThat(endpoint.warm().getStatus()).isEqualTo(503);

        warmer.warm();

        assertThat(warmer.ready()).isTrue();
        assertThat(endpoint.warm().getStatus()).isEqualTo(200);
        assertThat(endpoint.warm().getBody()).containsEntry("warm", true);
    }
}
