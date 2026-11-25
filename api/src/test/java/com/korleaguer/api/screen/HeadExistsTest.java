package com.korleaguer.api.screen;

import com.korleaguer.api.article.NewsListFinder;
import com.korleaguer.api.article.NewsPageFinder;
import com.korleaguer.api.card.MyPlayersService;
import com.korleaguer.api.game.GameDetailService;
import com.korleaguer.api.web.BaseballController;
import com.korleaguer.api.web.NewsController;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HEAD /api/players/{id}·/api/news/{id}는 있는지만 보고 답한다(웹이 선수·기사 화면을 열 때마다 묻는다).
 * GET의 화면 계산(선수 화면·같은 기사 묶음)을 부르지 않는지 본다. HEAD 처리가 빠져도 Spring이 GET으로 답해
 * 상태 코드는 같으므로, 상태 코드만 보는 테스트(ApiIntegrationTest·NewsApiTest)로는 알 수 없다
 */
class HeadExistsTest {

    private final BaseballQueryService queries = mock(BaseballQueryService.class);
    private final NewsPageFinder newsPages = mock(NewsPageFinder.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
            new BaseballController(queries, mock(MyPlayersService.class), mock(GameDetailService.class),
                    mock(PlayerTimelineService.class), mock(SameGamesService.class),
                    mock(CompareService.class)),
            new NewsController(newsPages, mock(NewsListFinder.class))).build();

    @Test
    @DisplayName("선수 주소에 HEAD로 물으면 선수 화면을 만들지 않고 있는지만 답한다")
    void playerHeadOnlyChecksThatThePlayerExists() throws Exception {
        // given
        when(queries.playerExists(1)).thenReturn(true);

        // when // then
        mockMvc.perform(head("/api/players/1")).andExpect(status().isOk());
        mockMvc.perform(head("/api/players/2")).andExpect(status().isNotFound());

        verify(queries, never()).player(anyLong());
    }

    @Test
    @DisplayName("기사 주소에 HEAD로 물으면 기사 화면을 만들지 않고 있는지만 답한다")
    void newsHeadOnlyChecksThatTheArticleIsShown() throws Exception {
        // given
        when(newsPages.exists(1)).thenReturn(true);

        // when // then
        mockMvc.perform(head("/api/news/1")).andExpect(status().isOk());
        mockMvc.perform(head("/api/news/2")).andExpect(status().isNotFound());

        verify(newsPages, never()).find(anyLong());
    }
}
