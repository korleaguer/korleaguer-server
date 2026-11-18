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

import java.time.Duration;
import java.time.Instant;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/news/{id}: 기사 한 건(JSON 모양). 같은 글을 실은 다른 언론사(alsoBy)는 뉴스 탭 목록 한 줄과 같다.
 * 없는 번호·뺀 언론사는 404, 숫자가 아니면 400. HEAD는 같은 기준의 상태만 준다
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class NewsApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NewsArticleRepository news;

    private int seq;

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
                .andExpect(jsonPath("$.more[0].title").value("노시환 MLB 첫 소식"))
                .andExpect(jsonPath("$.alsoBy").isArray()) // 같은 글을 실은 다른 언론사가 없으면 빈 목록(null 아님)
                .andExpect(jsonPath("$.alsoBy.length()").value(0));
    }

    @Test
    void alsoByIsTheSameAsTheNewsTabLine() throws Exception {
        // 마이데일리 기사를 스포츠조선·조선일보가 같은 제목으로 실었다(뺀 언론사 사본은 넣지 않는다, 이슈 #180)
        Instant at = Instant.parse("2026-11-14T12:00:00Z");
        NewsArticle first = save("이정후", "이정후 새 타격코치", "마이데일리", at);
        NewsArticle copy = save("이정후", "이정후 새 타격코치", "스포츠조선", at.plusSeconds(600));
        save("이정후", "이정후 새 타격코치", "없는 언론사", at.plusSeconds(900));
        save("이정후", "이정후 새 타격코치", "조선일보", at.plusSeconds(1200));

        mockMvc.perform(get("/api/news").param("player", "이정후"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(first.getId()))
                .andExpect(jsonPath("$.items[0].alsoBy", contains("스포츠조선", "조선일보")));
        mockMvc.perform(get("/api/news/" + first.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alsoBy", contains("스포츠조선", "조선일보")));
        // 대표가 아닌 사본을 열면 그 언론사는 빠지고 대표 언론사가 들어간다
        mockMvc.perform(get("/api/news/" + copy.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alsoBy", contains("마이데일리", "조선일보")));
    }

    @Test
    void anOldArticleFindsItsCopiesBeyondTheLatestArticles() throws Exception {
        // 뉴스 탭을 넘겨 연 오래된 기사: 뒤에 이 선수 기사가 많이 쌓여 최근 기사에 사본이 없어도 이 기사 앞뒤 이틀에서 찾는다
        Instant at = Instant.parse("2026-11-01T03:00:00Z");
        NewsArticle old = save("이정후", "이정후 연봉 조정 소식", "마이데일리", at);
        save("이정후", "이정후 연봉 조정 소식", "스포츠조선", at.plus(Duration.ofHours(1)));
        save("이정후", "이정후 연봉 조정 소식", "조선일보", at.plus(Duration.ofDays(3))); // 이틀 넘게 떨어지면 다른 기사
        for (int i = 0; i < NewsPageFinder.MORE * 4; i++) {
            save("이정후", "이정후 다른 소식 " + i, "마이데일리", at.plus(Duration.ofDays(5)).plus(Duration.ofMinutes(i)));
        }

        mockMvc.perform(get("/api/news/" + old.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alsoBy", contains("스포츠조선")))
                .andExpect(jsonPath("$.more.length()").value(NewsPageFinder.MORE));
    }

    @Test
    void copiesLinkedThroughAnotherCopyAreCountedLikeTheNewsTabLine() throws Exception {
        // 같은 제목이 36시간 간격으로 세 언론사에 실렸다. 첫 기사와 마지막 기사는 이틀 넘게 떨어졌지만 가운데 기사가 이어 줘
        // 목록은 한 줄로 묶는다. 기사 페이지도 그 기사 앞뒤 이틀에서 그치지 않고 넓혀 읽어 같은 언론사를 준다
        Instant at = Instant.parse("2026-11-08T00:00:00Z");
        NewsArticle first = save("이정후", "이정후 연봉 조정 소식", "마이데일리", at);
        save("이정후", "이정후 연봉 조정 소식", "스포츠조선", at.plus(Duration.ofHours(36)));
        NewsArticle last = save("이정후", "이정후 연봉 조정 소식", "조선일보", at.plus(Duration.ofHours(72)));

        mockMvc.perform(get("/api/news").param("player", "이정후"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(first.getId()))
                .andExpect(jsonPath("$.items[0].alsoBy", contains("스포츠조선", "조선일보")));
        mockMvc.perform(get("/api/news/" + first.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alsoBy", contains("스포츠조선", "조선일보")));
        mockMvc.perform(get("/api/news/" + last.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alsoBy", contains("마이데일리", "스포츠조선")));
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

    @Test
    void headAnswersOnlyWhetherTheArticleIsShown() throws Exception {
        // 웹이 기사 화면을 열 때마다 먼저 묻는다. GET과 같은 기준(없는 번호·뺀 언론사는 404)으로 본문 없이 상태만
        Instant at = Instant.parse("2026-11-10T03:00:00Z");
        NewsArticle shown = save("노시환", "노시환 MLB 소식", "마이데일리", at);
        NewsArticle dropped = save("노시환", "노시환 MLB 소식", "없는 언론사", at);

        mockMvc.perform(head("/api/news/" + shown.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
        mockMvc.perform(head("/api/news/" + dropped.getId())).andExpect(status().isNotFound());
        mockMvc.perform(head("/api/news/999999")).andExpect(status().isNotFound());
    }

    private NewsArticle save(String subject, String title, String publisher, Instant publishedAt) {
        int n = ++seq;
        return news.save(new NewsArticle(subject, "https://news.example/" + n, "s" + n, title, publisher, publishedAt,
                publishedAt.plusSeconds(60)));
    }
}
