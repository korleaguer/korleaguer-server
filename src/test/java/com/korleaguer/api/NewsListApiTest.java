package com.korleaguer.api;

import com.korleaguer.domain.Affiliation;
import com.korleaguer.domain.AffiliationRepository;
import com.korleaguer.domain.NewsArticle;
import com.korleaguer.domain.NewsArticleRepository;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerRepository;
import com.korleaguer.domain.Team;
import com.korleaguer.domain.TeamRepository;
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
import java.time.LocalDate;
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

    @Autowired
    private PlayerRepository players;

    @Autowired
    private TeamRepository teams;

    @Autowired
    private AffiliationRepository affiliations;

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
    void searchLooksAtTitleAndKoreanTitleOnly() throws Exception {
        save("이정후", "이정후 새 타격코치", "마이데일리", T0);
        save("이정후", "이정후 계약 소식", "마이데일리", T0.minusSeconds(60));
        NewsArticle english = save("이정후", "Lee extension talk", "MLB Trade Rumors", T0.minusSeconds(120));
        english.enriched(null, "이정후 계약 연장 논의", "자이언츠가 이정후와 이야기했다.", "claude-haiku-5-5", T0);
        news.save(english);
        NewsArticle bySummary = save("이정후", "이정후 겨울 훈련", "마이데일리", T0.minusSeconds(180));
        bySummary.enriched(null, null, "샌프란시스코와 맺은 계약 2년째를 앞두고 훈련한다.", "claude-haiku-5-5", T0);
        news.save(bySummary);
        save("김하성", "김하성 FA 계약 임박", "마이데일리", T0.minusSeconds(240));

        // 요약에만 있는 기사(이정후 겨울 훈련)는 나오지 않는다: 목록에 제목만 보여 왜 나왔는지 알 수 없다(이슈 #161)
        JsonNode all = page(null, null, "계약");
        assertThat(titles(all)).containsExactly("이정후 계약 소식", "Lee extension talk", "김하성 FA 계약 임박");
        assertThat(all.get("query").asString()).isEqualTo("계약");
        // 검색할 때도 많이 나온 말을 검색어와 상관없이 준다(알약을 눌러 거른 채로도 줄이 보이게, 이슈 #165). 여기는 3번 넘게 나온 말이 '계약'뿐
        assertThat(all.get("topics").size()).isEqualTo(1);
        assertThat(all.get("topics").get(0).get("word").asString()).isEqualTo("계약");
        assertThat(all.get("topics").get(0).get("count").asInt()).isEqualTo(3);

        // 선수 거르기와 함께
        assertThat(titles(page("이정후", null, " 계약 "))).containsExactly("이정후 계약 소식", "Lee extension talk");
        // 영어도 대소문자 상관없이
        assertThat(titles(page(null, null, "EXTENSION"))).containsExactly("Lee extension talk");
    }

    @Test
    void searchTreatsWildcardsAsLetters() throws Exception {
        save("이정후", "이정후 출루율 30% 돌파", "마이데일리", T0);
        save("이정후", "이정후 새 타격코치", "마이데일리", T0.minusSeconds(60));

        assertThat(titles(page(null, null, "%"))).containsExactly("이정후 출루율 30% 돌파");
        assertThat(titles(page(null, null, "_"))).isEmpty();
    }

    @Test
    void searchPagesThroughWithoutRepeats() throws Exception {
        for (int i = 0; i < 25; i++) {
            save("이정후", "이정후 FA 소식 " + i, "마이데일리", T0.minus(Duration.ofMinutes(10L * i)));
            save("이정후", "이정후 다른 소식 " + i, "마이데일리", T0.minus(Duration.ofMinutes(10L * i + 5)));
        }
        JsonNode first = page(null, null, "fa");
        assertThat(first.get("items").size()).isEqualTo(NewsListFinder.PAGE);
        JsonNode second = page(null, first.get("next").asString(), "fa");
        List<String> seen = new ArrayList<>(titles(first));
        seen.addAll(titles(second));
        assertThat(seen).hasSize(25).doesNotHaveDuplicates().allMatch(t -> t.contains("FA"));
        assertThat(second.hasNonNull("next")).isFalse();
    }

    @Test
    void topicsAreWordsThatRecentArticlesMentionOften() throws Exception {
        for (int i = 0; i < 4; i++) {
            save("김하성", "김하성 FA 시장 " + i, "마이데일리", T0.minusSeconds(60L * i));
        }
        for (int i = 0; i < 3; i++) {
            save("김혜성", "다저스 김혜성 이야기 " + i, "마이데일리", T0.minusSeconds(600 + 60L * i));
        }
        save("이정후", "메츠 관심 " + 1, "마이데일리", T0.minusSeconds(1200));
        save("이정후", "메츠 관심 " + 2, "마이데일리", T0.minusSeconds(1260)); // 2번뿐: 빠진다

        JsonNode all = page(null, null, null);
        assertThat(all.get("topics").size()).isEqualTo(2);
        assertThat(all.get("topics").get(0).get("word").asString()).isEqualTo("FA");
        assertThat(all.get("topics").get(0).get("count").asInt()).isEqualTo(4);
        assertThat(all.get("topics").get(1).get("word").asString()).isEqualTo("다저스");
        assertThat(all.get("topics").get(1).get("count").asInt()).isEqualTo(3);
        // 선수로 거르면 그 선수 기사에서만 센다
        JsonNode kim = page("김하성", null, null);
        assertThat(kim.get("topics").size()).isEqualTo(1);
        assertThat(kim.get("topics").get(0).get("word").asString()).isEqualTo("FA");
    }

    @Test
    void latinWordsMatchWholeWordsOnly() throws Exception {
        save("김하성", "김하성 FA 시장 1위", "마이데일리", T0);
        save("김혜성", "다저스, 김혜성 DFA 처리할 수도", "마이데일리", T0.minusSeconds(60)); // DFA(양도지명)는 FA가 아니다
        save("이정후", "Fans love Lee", "MLB Trade Rumors", T0.minusSeconds(120)); // fans 속 fa도 아니다
        for (int i = 0; i < 2; i++) {
            save("김하성", "김하성 FA 소식 " + i, "마이데일리", T0.minusSeconds(180 + 60L * i));
        }

        assertThat(titles(page(null, null, "FA"))).containsExactly("김하성 FA 시장 1위", "김하성 FA 소식 0", "김하성 FA 소식 1");
        JsonNode first = page(null, null, null);
        assertThat(first.get("topics").get(0).get("word").asString()).isEqualTo("FA");
        assertThat(first.get("topics").get(0).get("count").asInt()).isEqualTo(3);
        // 한글 말은 글자 포함 그대로
        assertThat(titles(page(null, null, "처리"))).containsExactly("다저스, 김혜성 DFA 처리할 수도");
    }

    @Test
    void oneTeamTakesOneTopicEvenWithTwoNames() throws Exception {
        for (int i = 0; i < 4; i++) {
            save("이정후", "샌프란시스코 자이언츠 이정후 소식 " + i, "마이데일리", T0.minusSeconds(60L * i));
        }
        save("이정후", "샌프란시스코 이야기", "마이데일리", T0.minusSeconds(600));

        // 전체 보기로 본다(이정후를 고르면 그 선수의 지금 구단이라 둘 다 빠진다, ownTeamIsNotATopicForThatPlayer)
        JsonNode page = page(null, null, null);
        List<String> words = new ArrayList<>();
        page.get("topics").forEach(t -> words.add(t.get("word").asString()));
        assertThat(words).contains("샌프란시스코").doesNotContain("자이언츠");
    }

    @Test
    void topicsStayWhileSearching() throws Exception {
        for (int i = 0; i < 3; i++) {
            save("김하성", "김하성 FA 시장 " + i, "마이데일리", T0.minusSeconds(60L * i));
            save("김하성", "김하성 영입 경쟁 " + i, "마이데일리", T0.minusSeconds(600 + 60L * i));
        }

        JsonNode searching = page("김하성", null, "FA");
        assertThat(titles(searching)).hasSize(3);
        List<String> words = new ArrayList<>();
        searching.get("topics").forEach(t -> words.add(t.get("word").asString()));
        // 검색어(FA)와 상관없이 그 선수의 최근 기사에서 센다. 같은 수면 정해 둔 순서(WORDS)
        assertThat(words).containsExactly("FA", "영입");
        // 다음 쪽에는 주지 않는다
        assertThat(page("김하성", "2026-11-14T11:00:00Z_1", "FA").get("topics")).isEmpty();
    }

    @Test
    void topicCountIsTheNumberOfSearchRowsBeyondRecentArticles() throws Exception {
        // 최근 TOPIC_ROWS건: 그중 FA가 3건. 그보다 오래된 FA 기사 5건은 말을 고를 때는 안 보지만 검색에는 나온다
        for (int i = 0; i < NewsListFinder.TOPIC_ROWS; i++) {
            String title = i < 3 ? "김하성 FA 최근 소식 " + i : "김하성 겨울 소식 " + i;
            save("김하성", title, "마이데일리", T0.minus(Duration.ofMinutes(i)));
        }
        for (int i = 0; i < 5; i++) {
            save("김하성", "김하성 FA 지난 소식 " + i, "마이데일리", T0.minus(Duration.ofDays(30 + i)));
        }

        JsonNode first = page("김하성", null, null);
        assertThat(first.get("topics").get(0).get("word").asString()).isEqualTo("FA");
        // 숫자는 알약을 눌렀을 때 나오는 줄 수(최근 3건만 세면 3이었다)
        assertThat(first.get("topics").get(0).get("count").asInt()).isEqualTo(8);
        JsonNode searched = page("김하성", null, "FA");
        assertThat(titles(searched)).hasSize(8);
        // 알약을 눌러 연 검색의 줄 수도 같은 8(이슈 #183)
        assertThat(queryCount(searched)).isEqualTo(8);
    }

    @Test
    void queryCountIsTheNumberOfSearchRowsOnTheFirstPageOnly() throws Exception {
        // FA 기사 25건(앞 2건은 다른 언론사도 같은 제목으로 실어 사본이 있다: 줄은 그대로 25), 다른 기사 25건
        for (int i = 0; i < 25; i++) {
            save("이정후", "이정후 FA 소식 " + i, "마이데일리", T0.minus(Duration.ofMinutes(10L * i)));
            save("이정후", "이정후 다른 소식 " + i, "마이데일리", T0.minus(Duration.ofMinutes(10L * i + 5)));
        }
        save("이정후", "이정후 FA 소식 0", "스포츠조선", T0.plusSeconds(60));
        save("이정후", "이정후 FA 소식 1", "스포츠조선", T0.minus(Duration.ofMinutes(9)));

        JsonNode first = page(null, null, "FA");
        assertThat(first.get("items").size()).isEqualTo(NewsListFinder.PAGE);
        assertThat(queryCount(first)).isEqualTo(25);
        JsonNode second = page(null, first.get("next").asString(), "FA");
        // 끝까지 넘겨 본 줄 수와 같다
        assertThat(titles(first).size() + titles(second).size()).isEqualTo(25);
        assertThat(second.hasNonNull("next")).isFalse();
        // 둘째 쪽에는 없다(웹은 첫 쪽 값을 그대로 둔다)
        assertThat(second.has("queryCount")).isTrue();
        assertThat(queryCount(second)).isNull();
        // 검색어가 없으면 없다
        assertThat(queryCount(page(null, null, null))).isNull();
        assertThat(queryCount(page(null, null, "  "))).isNull();
    }

    @Test
    void queryCountInAPlayerScopeCountsThatPlayersRowsLikeThePill() throws Exception {
        for (int i = 0; i < 4; i++) {
            save("김하성", "김하성 FA 시장 " + i, "마이데일리", T0.minusSeconds(60L * i));
        }
        save("김하성", "김하성 FA 시장 0", "스포츠조선", T0.plusSeconds(30)); // 같은 기사: 한 줄
        save("김하성", "김하성 겨울 훈련", "마이데일리", T0.minusSeconds(300)); // 검색어 없음
        for (int i = 0; i < 3; i++) {
            save("이정후", "이정후 FA 이야기 " + i, "마이데일리", T0.minusSeconds(600 + 60L * i));
        }

        // 선수를 고르면 그 선수 기사만 센다
        JsonNode kim = page("김하성", null, "FA");
        assertThat(titles(kim)).hasSize(4);
        assertThat(queryCount(kim)).isEqualTo(4).isEqualTo(topicCount(page("김하성", null, null), "FA"));
        // 전체 보기는 두 선수 기사 모두. 알약을 누른 채로 받은 첫 쪽의 알약 숫자와도 같다(같은 셈)
        JsonNode all = page(null, null, "FA");
        assertThat(titles(all)).hasSize(7);
        assertThat(queryCount(all)).isEqualTo(7).isEqualTo(topicCount(all, "FA"));
        // 대소문자는 가리지 않는다(검색과 같다)
        assertThat(queryCount(page(null, null, "fa"))).isEqualTo(7);
        // 알약에 없는 말도 센다
        assertThat(queryCount(page("김하성", null, "훈련"))).isEqualTo(1);
    }

    @Test
    void topicsAreOrderedByShownCount() throws Exception {
        // 최근엔 '영입'이 더 많지만(4 대 3), 전체로 세면 'FA'가 더 많다(3 + 오래된 3 = 6). 보이는 숫자 순으로 FA가 앞
        for (int i = 0; i < 4; i++) {
            save("김하성", "김하성 영입 경쟁 " + i, "마이데일리", T0.minus(Duration.ofMinutes(i)));
        }
        for (int i = 0; i < 3; i++) {
            save("김하성", "김하성 FA 시장 " + i, "마이데일리", T0.minus(Duration.ofMinutes(10 + i)));
        }
        for (int i = 0; i < NewsListFinder.TOPIC_ROWS; i++) {
            save("김하성", "김하성 겨울 소식 " + i, "마이데일리", T0.minus(Duration.ofHours(1)).minus(Duration.ofMinutes(i)));
        }
        for (int i = 0; i < 3; i++) {
            save("김하성", "김하성 FA 지난 소식 " + i, "마이데일리", T0.minus(Duration.ofDays(30 + i)));
        }

        List<String> shown = new ArrayList<>();
        page("김하성", null, null).get("topics").forEach(t -> shown.add(t.get("word").asString() + " " + t.get("count").asInt()));
        assertThat(shown).containsExactly("FA 6", "영입 4");
    }

    @Test
    void ownTeamIsNotATopicForThatPlayer() throws Exception {
        inDodgers(null);
        for (int i = 0; i < 4; i++) {
            save("김혜성", "다저스 김혜성 이야기 " + i, "마이데일리", T0.minusSeconds(60L * i));
        }
        for (int i = 0; i < 3; i++) {
            save("김혜성", "김혜성 트레이드설 " + i, "마이데일리", T0.minusSeconds(600 + 60L * i));
        }

        // 그 선수 기사에선 지금 구단(다저스)을 세지 않는다: 거의 모든 기사에 나와 눌러도 걸러지지 않는다
        JsonNode kimPage = page("김혜성", null, null);
        assertThat(kimPage.get("topics").size()).isEqualTo(1);
        assertThat(kimPage.get("topics").get(0).get("word").asString()).isEqualTo("트레이드");
        // 전체 보기에선 그대로 센다(어느 선수 이야기인지 고르는 바로가기)
        List<String> words = new ArrayList<>();
        page(null, null, null).get("topics").forEach(t -> words.add(t.get("word").asString()));
        assertThat(words).containsExactly("다저스", "트레이드");
    }

    @Test
    void lastTeamStaysATopicForAFreeAgent() throws Exception {
        inDodgers(LocalDate.of(2026, 11, 1));
        for (int i = 0; i < 3; i++) {
            save("김혜성", "다저스 김혜성 복귀설 " + i, "마이데일리", T0.minusSeconds(60L * i));
        }

        // FA면 마지막 구단도 행선지 후보라 뺄 까닭이 없다
        JsonNode kimPage = page("김혜성", null, null);
        assertThat(kimPage.get("topics").size()).isEqualTo(1);
        assertThat(kimPage.get("topics").get(0).get("word").asString()).isEqualTo("다저스");
    }

    /** 김혜성의 소속 줄을 다저스로(시험 DB에 이미 있으면 고친다). ended가 있으면 그날 소속이 끝난 것(FA)으로 */
    private void inDodgers(LocalDate ended) {
        Team dodgers = teams.findById(119L).orElseGet(() -> new Team(119L));
        dodgers.update("LAD", "Los Angeles Dodgers");
        teams.save(dodgers);
        Player kim = players.findByMlbId(808975L).orElseGet(() -> {
            Player p = new Player("김혜성");
            p.changeMlbId(808975L);
            return players.save(p);
        });
        Affiliation affiliation = affiliations.findByPlayer(kim).orElseGet(() -> new Affiliation(kim, dodgers, LocalDate.of(2025, 1, 4)));
        affiliation.changeTeam(dodgers);
        if (ended != null) {
            affiliation.end(ended);
        }
        affiliations.save(affiliation);
    }

    @Test
    void tooLongQueryIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/news").param("q", "가".repeat(31)))
                .andExpect(status().isBadRequest());
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
        return page(player, before, null);
    }

    private JsonNode page(String player, String before, String q) throws Exception {
        var request = get("/api/news");
        if (q != null) {
            request = request.param("q", q);
        }
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

    /** 검색 줄 수(queryCount). null이면 null */
    private static Integer queryCount(JsonNode page) {
        JsonNode count = page.get("queryCount");
        return count == null || count.isNull() ? null : count.asInt();
    }

    /** 알약 줄에서 그 말의 숫자. 없으면 null */
    private static Integer topicCount(JsonNode page, String word) {
        for (JsonNode topic : page.get("topics")) {
            if (topic.get("word").asString().equals(word)) {
                return topic.get("count").asInt();
            }
        }
        return null;
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
