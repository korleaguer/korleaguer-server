package com.korleaguer.news;

import com.korleaguer.config.DataMode;
import com.korleaguer.domain.DestinationMention;
import com.korleaguer.domain.NewsArticle;
import com.korleaguer.domain.NewsArticleRepository;
import com.korleaguer.domain.NewsDestination.Stance;
import com.korleaguer.domain.NewsDestinationRepository;
import com.korleaguer.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** 기사마다 행선지로 거론된 구단 고르기(NewsTagger): 고를 기사, 다시 고르기, 실패 처리, 홈이 셀 거론 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class NewsTaggerTest {

    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");

    @Autowired
    private NewsArticleRepository articles;
    @Autowired
    private NewsDestinationRepository destinations;
    @Autowired
    private PlatformTransactionManager transactions;

    /** 제목에 따라 정해 둔 답을 주는 분류기. 부른 제목을 적어 둔다 */
    private static final class FakeTagger implements DestinationTagger {
        final Map<String, Optional<List<Destination>>> answers;
        final List<String> asked = new ArrayList<>();
        String version = "fake/d1";
        /** 이 제목들은 '주인공 아님'으로 답한다 */
        final java.util.Set<String> offTopicTitles = new java.util.HashSet<>();
        RuntimeException failure;

        FakeTagger(Map<String, Optional<List<Destination>>> answers) {
            this.answers = answers;
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public Optional<Tagging> tag(String player, String title, String text) {
            if (failure != null) {
                throw failure;
            }
            asked.add(title);
            if (offTopicTitles.contains(title)) {
                return Optional.of(new Tagging(List.of(), false));
            }
            return answers.getOrDefault(title, Optional.of(List.<Destination>of())).map(d -> new Tagging(d, true));
        }

        @Override
        public String version() {
            return version;
        }
    }

    private NewsTagger tagger(FakeTagger fake) {
        return new NewsTagger(articles, destinations, fake, DataMode.MLB, new MutableClock(NOW), transactions);
    }

    @Test
    void tagsSummarizedArticlesAndHomeCanCountTheMentions() {
        article("메츠행 급부상", "2026-11-12T01:00:00Z", true, 0);
        article("필라델피아 동반 영입설", "2026-11-11T18:00:00Z", true, 0);
        article("노시환 수비 호평", "2026-11-11T10:00:00Z", true, 0);
        FakeTagger fake = new FakeTagger(Map.of(
                "메츠행 급부상", Optional.of(List.of(new DestinationTagger.Destination("NYM", Stance.LINKED),
                        new DestinationTagger.Destination("NYY", Stance.DOUBTED))),
                "필라델피아 동반 영입설", Optional.of(List.of(new DestinationTagger.Destination("PHI", Stance.LINKED)))));

        assertThat(tagger(fake).tag()).isEqualTo(3);

        assertThat(destinations.findMentions("노시환", Instant.parse("2026-11-05T15:00:00Z"), java.util.Set.of("마이데일리")))
                .extracting(DestinationMention::team, DestinationMention::stance)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("NYM", Stance.LINKED),
                        org.assertj.core.groups.Tuple.tuple("NYY", Stance.DOUBTED),
                        org.assertj.core.groups.Tuple.tuple("PHI", Stance.LINKED));
        // 고른 기사는 다시 고르지 않는다(거론된 구단이 없던 기사도)
        FakeTagger again = new FakeTagger(Map.of());
        assertThat(tagger(again).tag()).isZero();
        assertThat(again.asked).isEmpty();
    }

    @Test
    void waitsForTheSummaryButTagsArticlesWhoseSummaryWasGivenUp() {
        article("요약 기다리는 기사", "2026-11-12T01:00:00Z", false, 0);
        article("요약 포기한 기사", "2026-11-12T00:00:00Z", false, 3);
        FakeTagger fake = new FakeTagger(Map.of());

        tagger(fake).tag();

        assertThat(fake.asked).containsExactly("요약 포기한 기사");
    }

    @Test
    void aNewPromptVersionRetagsAndReplacesTheOldTeams() {
        article("메츠행 급부상", "2026-11-12T01:00:00Z", true, 0);
        FakeTagger first = new FakeTagger(Map.of("메츠행 급부상",
                Optional.of(List.of(new DestinationTagger.Destination("ATL", Stance.LINKED)))));
        tagger(first).tag();

        FakeTagger second = new FakeTagger(Map.of("메츠행 급부상",
                Optional.of(List.of(new DestinationTagger.Destination("NYM", Stance.LINKED)))));
        second.version = "fake/d2";
        assertThat(tagger(second).tag()).isEqualTo(1);

        assertThat(destinations.findMentions("노시환", Instant.parse("2026-11-01T00:00:00Z"), java.util.Set.of("마이데일리")))
                .extracting(DestinationMention::team).containsExactly("NYM");
    }

    @Test
    void aSummaryArrivingAfterTaggingTagsTheArticleAgain() {
        NewsArticle article = article("요약 포기한 기사", "2026-11-12T01:00:00Z", false, 3);
        FakeTagger fake = new FakeTagger(Map.of());
        tagger(fake).tag();
        article.enriched(null, null, "늦게 붙은 요약.", "claude-haiku-5-5/v4", NOW.plusSeconds(60));
        articles.save(article);

        tagger(fake).tag();

        assertThat(fake.asked).containsExactly("요약 포기한 기사", "요약 포기한 기사");
    }

    @Test
    void anArticleWhereThePlayerIsNotTheSubjectIsMarkedOffTopic() {
        NewsArticle kbo = article("김도영→유격수, 노시환→미국행…최고 3루수 자리", "2026-11-12T01:00:00Z", true, 0);
        NewsArticle roh = article("노시환 메츠행 급부상", "2026-11-12T02:00:00Z", true, 0);
        FakeTagger fake = new FakeTagger(Map.of());
        fake.offTopicTitles.add("김도영→유격수, 노시환→미국행…최고 3루수 자리");

        tagger(fake).tag();

        assertThat(articles.findById(kbo.getId()).orElseThrow().isOffTopic()).isTrue();
        assertThat(articles.findById(roh.getId()).orElseThrow().getOffTopic()).isFalse();
    }

    @Test
    void anUnreadableAnswerCountsAsAnAttemptAndGivesUpAfterThree() {
        NewsArticle article = article("이상한 답", "2026-11-12T01:00:00Z", true, 0);
        FakeTagger fake = new FakeTagger(Map.of("이상한 답", Optional.empty()));

        for (int i = 0; i < NewsTagger.MAX_ATTEMPTS + 1; i++) {
            tagger(fake).tag();
        }

        assertThat(fake.asked).hasSize(NewsTagger.MAX_ATTEMPTS);
        assertThat(articles.findById(article.getId()).orElseThrow().getDestinationAttempts()).isEqualTo(3);
        assertThat(articles.findById(article.getId()).orElseThrow().getDestinationsAt()).isNull();
    }

    @Test
    void aServiceFailureStopsTheRunWithoutCountingAnAttempt() {
        NewsArticle article = article("메츠행 급부상", "2026-11-12T01:00:00Z", true, 0);
        FakeTagger fake = new FakeTagger(Map.of());
        fake.failure = new Summarizer.Failure("overloaded", null);

        assertThat(tagger(fake).tag()).isZero();

        assertThat(articles.findById(article.getId()).orElseThrow().getDestinationAttempts()).isZero();
    }

    @Test
    void nothingInSampleModeOrWhenTheTaggerIsOff() {
        article("메츠행 급부상", "2026-11-12T01:00:00Z", true, 0);
        FakeTagger fake = new FakeTagger(Map.of());

        assertThat(new NewsTagger(articles, destinations, fake, DataMode.SAMPLE, new MutableClock(NOW), transactions).tag())
                .isZero();
        assertThat(new NewsTagger(articles, destinations, DestinationTagger.OFF, DataMode.MLB, new MutableClock(NOW),
                transactions).tag()).isZero();
        assertThat(fake.asked).isEmpty();
    }

    private NewsArticle article(String title, String publishedAt, boolean summarized, int failedSummaries) {
        NewsArticle a = new NewsArticle("노시환", "https://news.example.kr/" + title.hashCode(), "h" + title.hashCode(), title,
                "마이데일리", Instant.parse(publishedAt), Instant.parse(publishedAt));
        if (summarized) {
            a.enriched(null, null, title + " 요약.", "claude-haiku-5-5/v4", NOW);
        }
        for (int i = 0; i < failedSummaries; i++) {
            a.enrichFailed(NOW);
        }
        return articles.save(a);
    }
}
