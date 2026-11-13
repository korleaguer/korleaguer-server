package com.korleaguer.api;

import com.korleaguer.domain.NewsArticle;
import com.korleaguer.domain.NewsArticleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /api/news/{id}: 기사 한 건(JSON 모양). 없는 번호·뺀 언론사는 404, 숫자가 아니면 400 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class NewsApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NewsArticleRepository news;

    @Test
    void servesOneArticleWithTheOtherArticlesOfThePlayer() throws Exception {
        NewsArticle older = news.save(new NewsArticle("노시환", "https://www.mydaily.co.kr/1", "a1", "노시환 MLB 첫 소식",
                "마이데일리", Instant.parse("2026-11-02T03:00:00Z"), Instant.parse("2026-11-02T03:10:00Z")));
        NewsArticle article = new NewsArticle("노시환", "https://www.mlbtraderumors.com/roh", "a2", "Roh Posted",
                "MLB Trade Rumors", Instant.parse("2026-11-10T03:00:00Z"), Instant.parse("2026-11-10T03:10:00Z"));
        article.enriched("https://www.mlbtraderumors.com/roh.jpg", "노시환 포스팅 공시", "한화가 노시환을 포스팅했다.",
                "claude-haiku-5-5", Instant.parse("2026-11-10T03:30:00Z"));
        article = news.save(article);

        mockMvc.perform(get("/api/news/" + article.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(article.getId()))
                .andExpect(jsonPath("$.player").value("노시환"))
                .andExpect(jsonPath("$.title").value("Roh Posted"))
                .andExpect(jsonPath("$.titleKo").value("노시환 포스팅 공시"))
                .andExpect(jsonPath("$.url").value("https://www.mlbtraderumors.com/roh"))
                .andExpect(jsonPath("$.publisher").value("MLB Trade Rumors"))
                .andExpect(jsonPath("$.publishedAt").value("2026-11-10T03:00:00Z"))
                .andExpect(jsonPath("$.imageUrl").value("https://www.mlbtraderumors.com/roh.jpg"))
                .andExpect(jsonPath("$.summary").value("한화가 노시환을 포스팅했다."))
                .andExpect(jsonPath("$.more.length()").value(1))
                .andExpect(jsonPath("$.more[0].id").value(older.getId()))
                .andExpect(jsonPath("$.more[0].title").value("노시환 MLB 첫 소식"));
    }

    @Test
    void unknownOrDroppedArticlesAreNotFound() throws Exception {
        NewsArticle dropped = news.save(new NewsArticle("노시환", "https://example.com/dropped", "d1", "노시환 MLB 소식",
                "없는 언론사", Instant.parse("2026-11-10T03:00:00Z"), Instant.parse("2026-11-10T03:10:00Z")));

        mockMvc.perform(get("/api/news/" + dropped.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("news not found"));
        mockMvc.perform(get("/api/news/999999")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/news/abc")).andExpect(status().isBadRequest());
    }
}
