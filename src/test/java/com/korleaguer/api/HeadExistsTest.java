package com.korleaguer.api;

import com.korleaguer.ingest.OnDemandSeason;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HEAD /api/players/{id}·/api/news/{id}는 있는지만 보고 답한다(웹이 선수·기사 화면을 열 때마다 묻는다).
 * GET의 화면 계산(시즌 받기·선수 화면·같은 기사 묶음)을 부르지 않는지 본다. HEAD 처리가 빠져도 Spring이 GET으로 답해
 * 상태 코드는 같으므로, 상태 코드만 보는 테스트(ApiIntegrationTest·NewsApiTest)로는 알 수 없다
 */
class HeadExistsTest {

    private final BaseballQueryService queries = mock(BaseballQueryService.class);
    private final OnDemandSeason onDemandSeason = mock(OnDemandSeason.class);
    private final NewsPageFinder newsPages = mock(NewsPageFinder.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
            new BaseballController(queries, mock(MyPlayersService.class), onDemandSeason, mock(GameDetailService.class),
                    mock(PlayerTimelineService.class), mock(SameGamesService.class)),
            new NewsController(newsPages, mock(NewsListFinder.class))).build();

    @Test
    void playerHeadOnlyChecksThatThePlayerExists() throws Exception {
        when(queries.playerExists(1)).thenReturn(true);

        mockMvc.perform(head("/api/players/1")).andExpect(status().isOk());
        mockMvc.perform(head("/api/players/2")).andExpect(status().isNotFound());

        verify(queries, never()).player(anyLong());
        verifyNoInteractions(onDemandSeason);
    }

    @Test
    void newsHeadOnlyChecksThatTheArticleIsShown() throws Exception {
        when(newsPages.exists(1)).thenReturn(true);

        mockMvc.perform(head("/api/news/1")).andExpect(status().isOk());
        mockMvc.perform(head("/api/news/2")).andExpect(status().isNotFound());

        verify(newsPages, never()).find(anyLong());
    }
}
