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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/news: 뉴스 탭 한 쪽(이슈 #153). 실제 명단(roster/baseball.yml)·포스팅(posting.yml, 이번 겨울 노시환)·언론사(news/sources.yml)
 * 설정과 메모리 DB로 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class NewsListApiTest {

    private static final Instant T0 = Instant.parse("2026-11-14T12:00:00Z");
    /** 같은 글로 볼 만큼 긴 첫머리(3글자 조각이 겹친다) */
    private static final String LEAD = "샌프란시스코 자이언츠가 이번 겨울 외야진을 정리하면서 이정후의 자리와 연봉 구조에 관심이 쏠리고 있다는 현지 보도가 나왔다";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NewsArticleRepository news;

    private final JsonMapper json = JsonMapper.builder().build();
    private int seq;

    @Test
    void playersAreThoseWithArticlesPostingFirstThenRosterOrder() throws Exception {
        save("김하성", "김하성 FA 소식", "마이데일리", T0);
        save("이정후", "이정후 새 타격코치", "마이데일리", T0.minusSeconds(60));
        save("노시환", "노시환 MLB 포스팅 공시", "마이데일리", T0.minusSeconds(120));
        // 뺀 언론사·주인공 아닌 기사만 있는 선수는 고르기 목록에 없다
        save("김혜성", "김혜성 다저스 소식", "없는 언론사", T0);
        offTopic(save("배지환", "배지환이 잠깐 나온 기사", "마이데일리", T0));

        mockMvc.perform(get("/api/news"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.players.length()").value(3))
                .andExpect(jsonPath("$.players[0].nameKo").value("노시환"))
                .andExpect(jsonPath("$.players[0].posting").value(true))
                .andExpect(jsonPath("$.players[0].playerId").doesNotExist())
                .andExpect(jsonPath("$.players[1].nameKo").value("이정후"))
                .andExpect(jsonPath("$.players[1].posting").value(false))
                .andExpect(jsonPath("$.players[1].latestAt").value("2026-11-14T11:59:00Z"))
                .andExpect(jsonPath("$.players[2].nameKo").value("김하성"))
                .andExpect(jsonPath("$.player").doesNotExist())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].title").value("김하성 FA 소식"))
                .andExpect(jsonPath("$.items[0].who[0]").value("김하성"))
                .andExpect(jsonPath("$.items[2].title").value("노시환 MLB 포스팅 공시"))
                .andExpect(jsonPath("$.next").doesNotExist());
    }

    @Test
    void onePlayerKeepsOnlyThatPlayersArticles() throws Exception {
        save("김하성", "김하성 FA 소식", "마이데일리", T0);
        save("이정후", "이정후 새 타격코치", "마이데일리", T0.minusSeconds(60));

        mockMvc.perform(get("/api/news").param("player", "이정후"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.player").value("이정후"))
                .andExpect(jsonPath("$.players.length()").value(2))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].title").value("이정후 새 타격코치"));
    }

    @Test
    void postingPlayersDomesticArticlesMustBeAboutMlb() throws Exception {
        save("노시환", "노시환 MLB 포스팅 공시", "마이데일리", T0);
        save("노시환", "한화 노시환 연봉 협상", "마이데일리", T0.minusSeconds(60)); // KBO 이야기
        save("노시환", "Roh Posted", "MLB Trade Rumors", T0.minusSeconds(120)); // 영어 매체는 그대로

        JsonNode page = page("노시환", null);

        assertThat(titles(page)).containsExactly("노시환 MLB 포스팅 공시", "Roh Posted");
    }

    @Test
    void latestAtIsTheNewestArticleThatTheListShows() throws Exception {
        save("이정후", "이정후 새 타격코치", "마이데일리", T0.minusSeconds(600));
        save("이정후", "이정후 연봉 이야기", "마이데일리", T0);
        offTopic(save("이정후", "이정후가 잠깐 나온 기사", "마이데일리", T0.plusSeconds(600))); // 주인공 아님: 세지 않는다
        save("이정후", "이정후 다른 소식", "없는 언론사", T0.plusSeconds(1200)); // 뺀 언론사: 세지 않는다
        save("노시환", "한화 노시환 연봉 협상", "마이데일리", T0.plusSeconds(600)); // 포스팅 선수의 KBO 기사: 세지 않는다
        save("노시환", "노시환 MLB 포스팅 공시", "마이데일리", T0.minusSeconds(60));

        mockMvc.perform(get("/api/news"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.players[0].nameKo").value("노시환"))
                .andExpect(jsonPath("$.players[0].latestAt").value("2026-11-14T11:59:00Z"))
                .andExpect(jsonPath("$.players[1].nameKo").value("이정후"))
                .andExpect(jsonPath("$.players[1].latestAt").value("2026-11-14T12:00:00Z"));
    }

    @Test
    void theSameArticleUnderTwoPlayersIsOneRowWithBothNames() throws Exception {
        save("김혜성", "다저스 김혜성·김하성 맞대결", "마이데일리", T0);
        save("김하성", "다저스 김혜성·김하성 맞대결", "마이데일리", T0);
        save("김하성", "김하성 FA 소식", "스포츠조선", T0.minusSeconds(3600));

        JsonNode page = page(null, null);

        assertThat(titles(page)).containsExactly("다저스 김혜성·김하성 맞대결", "김하성 FA 소식");
        JsonNode who = page.get("items").get(0).get("who");
        assertThat(List.of(who.get(0).asString(), who.get(1).asString())).containsExactlyInAnyOrder("김혜성", "김하성");
    }

    @Test
    void pagingThroughEverythingShowsEachArticleOnce() throws Exception {
        // 이정후 기사 130건(10분 간격). 여러 건은 다른 언론사가 3분 먼저 같은 제목으로 냈다(한 줄로 묶이고 그 언론사가 대표).
        // 기사는 모두 170건 남짓이라 한 번에 읽는 수(BATCH 100)와 한 쪽 줄 수(PAGE 20)의 경계 여러 곳에 같은 기사 쌍이 걸친다.
        // 그래도 묶음이 두 쪽에 나뉘어 보이거나 빠지지 않아야 한다
        Set<String> expected = new HashSet<>();
        int copies = 0;
        for (int i = 0; i < 130; i++) {
            Instant at = T0.minus(Duration.ofMinutes(10L * i));
            String title = "이정후 소식 " + i;
            save("이정후", title, "마이데일리", at);
            if (i % 4 == 0 || (i >= 15 && i <= 25) || (i >= 35 && i <= 45) || (i >= 75 && i <= 85)) {
                save("이정후", title, "스포츠조선", at.minus(Duration.ofMinutes(3)));
                copies++;
            }
            expected.add(title);
        }

        List<String> seen = new ArrayList<>();
        Set<Long> ids = new HashSet<>();
        int withCopies = 0;
        String before = null;
        int pages = 0;
        do {
            JsonNode page = page("이정후", before);
            assertThat(page.get("items").size()).isLessThanOrEqualTo(NewsListFinder.PAGE);
            for (JsonNode item : page.get("items")) {
                seen.add(item.get("title").asString());
                assertThat(ids.add(item.get("id").asLong())).isTrue();
                if (!item.get("alsoBy").isEmpty()) {
                    withCopies++;
                }
            }
            before = page.hasNonNull("next") ? page.get("next").asString() : null;
            pages++;
        } while (before != null && pages < 20);

        assertThat(pages).isEqualTo(7); // 20줄 6쪽 + 10줄
        assertThat(seen).hasSize(130).doesNotHaveDuplicates();
        assertThat(new HashSet<>(seen)).isEqualTo(expected);
        assertThat(withCopies).isEqualTo(copies); // 같은 기사 쌍은 모두 한 줄로 묶였다
        // 늦은 순
        assertThat(seen.getFirst()).isEqualTo("이정후 소식 0");
        assertThat(seen.getLast()).isEqualTo("이정후 소식 129");
    }

    @Test
    void aCopyNewerThanTheCursorStaysWithItsGroupOnTheNextPage() throws Exception {
        // 1~20번째 줄: 10분 간격 기사 20건. 커서는 20번째 줄(190분 전)이다
        for (int i = 0; i < 20; i++) {
            save("이정후", "이정후 소식 " + i, "마이데일리", T0.minus(Duration.ofMinutes(10L * i)));
        }
        // 21번째 묶음: 원문은 커서보다 이르고(195분 전), 요약이 붙은 사본은 커서보다 늦다(185분 전, 대표 기사)
        save("이정후", "이정후 타격코치 영입", "스포츠조선", T0.minus(Duration.ofMinutes(195)));
        NewsArticle summarized = save("이정후", "이정후 타격코치 영입", "MLB Trade Rumors", T0.minus(Duration.ofMinutes(185)));
        summarized.enriched(null, "이정후 타격코치 영입", "요약", "claude-haiku-5-5", T0);
        news.save(summarized);
        // 22번째 묶음: 서로는 다른 글로 보이는 두 사본(제목도 첫머리도 다르다)을 커서보다 늦은 기사가 이어 준다
        NewsArticle o1 = save("이정후", "이정후 연봉 이야기", "마이데일리", T0.minus(Duration.ofMinutes(196)));
        NewsArticle o2 = save("이정후", "샌프란시스코 외야 정리", "스포츠조선", T0.minus(Duration.ofMinutes(198)));
        o2.setLeadText(LEAD);
        news.save(o2);
        NewsArticle bridge = save("이정후", "이정후 연봉 이야기", "매일경제", T0.minus(Duration.ofMinutes(186)));
        bridge.setLeadText(LEAD);
        news.save(bridge);
        assertThat(o1.getId()).isNotNull();

        JsonNode first = page("이정후", null);
        assertThat(first.get("items").size()).isEqualTo(20);
        JsonNode second = page("이정후", first.get("next").asString());

        assertThat(titles(second)).containsExactly("이정후 타격코치 영입", "샌프란시스코 외야 정리");
        JsonNode coach = second.get("items").get(0);
        assertThat(coach.get("publisher").asString()).isEqualTo("MLB Trade Rumors"); // 요약이 붙은 사본이 대표
        assertThat(coach.get("alsoBy").get(0).asString()).isEqualTo("스포츠조선");
        JsonNode chained = second.get("items").get(1);
        assertThat(chained.get("alsoBy")).hasSize(2); // 이어 주는 기사가 빠지면 두 줄로 나뉜다
        assertThat(second.hasNonNull("next")).isFalse();
    }

    @Test
    void postingPlayerWithOnlyDomesticKboArticlesIsNotAChoice() throws Exception {
        save("노시환", "한화 노시환 연봉 협상", "마이데일리", T0); // MLB 이야기가 아니다
        save("이정후", "이정후 새 타격코치", "마이데일리", T0);

        mockMvc.perform(get("/api/news"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.players.length()").value(1))
                .andExpect(jsonPath("$.players[0].nameKo").value("이정후"));
    }

    @Test
    void copiesShowOtherPublishers() throws Exception {
        save("이정후", "이정후 새 타격코치", "마이데일리", T0);
        save("이정후", "이정후 새 타격코치", "스포츠조선", T0.plusSeconds(600));

        JsonNode item = page("이정후", null).get("items").get(0);

        assertThat(item.get("publisher").asString()).isEqualTo("마이데일리");
        assertThat(item.get("alsoBy").get(0).asString()).isEqualTo("스포츠조선");
    }

    @Test
    void unknownPlayerIsAnEmptyPage() throws Exception {
        save("이정후", "이정후 새 타격코치", "마이데일리", T0);

        mockMvc.perform(get("/api/news").param("player", "없는선수"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.player").doesNotExist())
                .andExpect(jsonPath("$.players.length()").value(1))
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.next").doesNotExist());
    }

    @Test
    void malformedCursorIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/news").param("before", "어제"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/news").param("before", "2026-11-14T12:00:00Z_abc"))
                .andExpect(status().isBadRequest());
    }

    private JsonNode page(String player, String before) throws Exception {
        var request = get("/api/news");
        if (player != null) {
            request = request.param("player", player);
        }
        if (before != null) {
            request = request.param("before", before);
        }
        String body = mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return json.readTree(body);
    }

    private static List<String> titles(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("items").forEach(item -> out.add(item.get("title").asString()));
        return out;
    }

    private NewsArticle save(String subject, String title, String publisher, Instant publishedAt) {
        int n = ++seq;
        return news.save(new NewsArticle(subject, "https://news.example/" + n, "h" + n, title, publisher, publishedAt,
                publishedAt.plusSeconds(60)));
    }

    private void offTopic(NewsArticle article) {
        article.destinationsTagged("claude-haiku-5-5/v1", T0, true);
        news.save(article);
    }
}
