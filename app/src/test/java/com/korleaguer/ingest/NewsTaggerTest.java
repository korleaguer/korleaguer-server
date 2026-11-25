package com.korleaguer.ingest;

import com.korleaguer.config.DataMode;
import com.korleaguer.domain.DestinationMention;
import com.korleaguer.domain.NewsArticle;
import com.korleaguer.domain.NewsArticleRepository;
import com.korleaguer.domain.NewsDestination.Stance;
import com.korleaguer.domain.NewsDestinationRepository;
import com.korleaguer.news.DestinationTagger.Kind;
import com.korleaguer.news.DestinationTagger;
import com.korleaguer.news.Summarizer;
import com.korleaguer.posting.PostingProperties;
import com.korleaguer.posting.Postings;
import com.korleaguer.support.CommittedDbTest;
import com.korleaguer.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** 기사마다 행선지로 거론된 구단 고르기(NewsTagger): 고를 기사, 다시 고르기, 실패 처리, 홈이 셀 거론 */
@CommittedDbTest
class NewsTaggerTest {

    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
    /** 이번 겨울 포스팅 선수는 노시환 */
    private static final Postings POSTINGS = new Postings(new PostingProperties(List.of(new PostingProperties.Current(
            "노시환", null, "한화 이글스", "3루수", 2000, List.of("Si-Hwan Roh"), "2026-11-02T08:00:00-05:00",
            "2026-12-01T17:00:00-05:00", null, null, null, null)), List.of()));

    @Autowired
    private NewsArticleRepository articles;
    @Autowired
    private NewsDestinationRepository destinations;
    @Autowired
    private PlatformTransactionManager transactions;

    @BeforeEach
    void emptyTables() {
        destinations.deleteAllInBatch();
        articles.deleteAllInBatch();
    }

    /** 제목에 따라 정해 둔 답을 주는 분류기. 부른 제목과 선수 처지를 적어 둔다 */
    private static final class FakeTagger implements DestinationTagger {
        final Map<String, Optional<List<Destination>>> answers;
        final List<String> asked = new ArrayList<>();
        final List<Kind> kinds = new ArrayList<>();
        String version = "fake/d1";
        String mlbVersion = "fake/m1";
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
        public Optional<Tagging> tag(String player, Kind kind, String title, String text) {
            if (failure != null) {
                throw failure;
            }
            asked.add(title);
            kinds.add(kind);
            if (offTopicTitles.contains(title)) {
                return Optional.of(new Tagging(List.of(), false));
            }
            return answers.getOrDefault(title, Optional.of(List.<Destination>of())).map(d -> new Tagging(d, true));
        }

        @Override
        public String version(Kind kind) {
            return kind == Kind.POSTING ? version : mlbVersion;
        }
    }

    private NewsTagger tagger(FakeTagger fake) {
        return new NewsTagger(articles, destinations, fake, POSTINGS, DataMode.MLB, new MutableClock(NOW), transactions);
    }

    @Test
    @DisplayName("요약된 기사에서 거론 구단을 고르고 이미 고른 기사는 다시 고르지 않는다")
    void tagsSummarizedArticlesAndHomeCanCountTheMentions() {
        // given
        article("메츠행 급부상", "2026-11-12T01:00:00Z", true, 0);
        article("필라델피아 동반 영입설", "2026-11-11T18:00:00Z", true, 0);
        article("노시환 수비 호평", "2026-11-11T10:00:00Z", true, 0);
        FakeTagger fake = new FakeTagger(Map.of(
                "메츠행 급부상", Optional.of(List.of(new DestinationTagger.Destination("NYM", Stance.LINKED),
                        new DestinationTagger.Destination("NYY", Stance.DOUBTED))),
                "필라델피아 동반 영입설", Optional.of(List.of(new DestinationTagger.Destination("PHI", Stance.LINKED)))));

        // when // then
        assertThat(tagger(fake).tag()).isEqualTo(3);

        assertThat(destinations.findMentions("노시환", Instant.parse("2026-11-05T15:00:00Z"), java.util.Set.of("마이데일리")))
                .extracting(DestinationMention::team, DestinationMention::stance)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("NYM", Stance.LINKED),
                        org.assertj.core.groups.Tuple.tuple("NYY", Stance.DOUBTED),
                        org.assertj.core.groups.Tuple.tuple("PHI", Stance.LINKED));

        // given
        // 고른 기사는 다시 고르지 않는다(거론된 구단이 없던 기사도)
        FakeTagger again = new FakeTagger(Map.of());

        // when // then
        assertThat(tagger(again).tag()).isZero();
        assertThat(again.asked).isEmpty();
    }

    @Test
    @DisplayName("요약을 기다리는 기사는 두고 요약을 포기한 기사에서 구단을 고른다")
    void waitsForTheSummaryButTagsArticlesWhoseSummaryWasGivenUp() {
        // given
        article("요약 기다리는 기사", "2026-11-12T01:00:00Z", false, 0);
        article("요약 포기한 기사", "2026-11-12T00:00:00Z", false, 3);
        FakeTagger fake = new FakeTagger(Map.of());

        // when
        tagger(fake).tag();

        // then
        assertThat(fake.asked).containsExactly("요약 포기한 기사");
    }

    @Test
    @DisplayName("지시문 판이 바뀌면 구단을 다시 골라 옛 구단을 바꾼다")
    void aNewPromptVersionRetagsAndReplacesTheOldTeams() {
        // given
        article("메츠행 급부상", "2026-11-12T01:00:00Z", true, 0);
        FakeTagger first = new FakeTagger(Map.of("메츠행 급부상",
                Optional.of(List.of(new DestinationTagger.Destination("ATL", Stance.LINKED)))));
        tagger(first).tag();

        FakeTagger second = new FakeTagger(Map.of("메츠행 급부상",
                Optional.of(List.of(new DestinationTagger.Destination("NYM", Stance.LINKED)))));
        second.version = "fake/d2";

        // when // then
        assertThat(tagger(second).tag()).isEqualTo(1);

        assertThat(destinations.findMentions("노시환", Instant.parse("2026-11-01T00:00:00Z"), java.util.Set.of("마이데일리")))
                .extracting(DestinationMention::team).containsExactly("NYM");
    }

    @Test
    @DisplayName("구단을 고른 뒤에 요약이 붙으면 그 기사를 다시 고른다")
    void aSummaryArrivingAfterTaggingTagsTheArticleAgain() {
        // given
        NewsArticle article = article("요약 포기한 기사", "2026-11-12T01:00:00Z", false, 3);
        FakeTagger fake = new FakeTagger(Map.of());
        tagger(fake).tag();
        // 구단을 고른 뒤의 기사를 다시 읽어 요약을 붙인다(처음 저장한 객체를 쓰면 고른 표시가 지워진다)
        NewsArticle tagged = articles.findById(article.getId()).orElseThrow();
        assertThat(tagged.getDestinationsAt()).isNotNull();
        tagged.enriched(null, null, "늦게 붙은 요약.", "claude-haiku-5-5/v4", NOW.plusSeconds(60));
        articles.save(tagged);

        // when
        tagger(fake).tag();

        // then
        assertThat(fake.asked).containsExactly("요약 포기한 기사", "요약 포기한 기사");
    }

    @Test
    @DisplayName("선수가 주인공이 아닌 기사만 주제에서 벗어난 기사로 표시한다")
    void anArticleWhereThePlayerIsNotTheSubjectIsMarkedOffTopic() {
        // given
        NewsArticle kbo = article("김도영→유격수, 노시환→미국행…최고 3루수 자리", "2026-11-12T01:00:00Z", true, 0);
        NewsArticle roh = article("노시환 메츠행 급부상", "2026-11-12T02:00:00Z", true, 0);
        FakeTagger fake = new FakeTagger(Map.of());
        fake.offTopicTitles.add("김도영→유격수, 노시환→미국행…최고 3루수 자리");

        // when
        tagger(fake).tag();

        // then
        assertThat(articles.findById(kbo.getId()).orElseThrow().isOffTopic()).isTrue();
        assertThat(articles.findById(roh.getId()).orElseThrow().getOffTopic()).isFalse();
    }

    @Test
    @DisplayName("읽을 수 없는 답이 세 번 나오면 그 기사의 구단 고르기를 포기한다")
    void anUnreadableAnswerCountsAsAnAttemptAndGivesUpAfterThree() {
        // given
        NewsArticle article = article("이상한 답", "2026-11-12T01:00:00Z", true, 0);
        FakeTagger fake = new FakeTagger(Map.of("이상한 답", Optional.empty()));

        // when
        for (int i = 0; i < NewsTagger.MAX_ATTEMPTS + 1; i++) {
            tagger(fake).tag();
        }

        // then
        assertThat(fake.asked).hasSize(NewsTagger.MAX_ATTEMPTS);
        assertThat(articles.findById(article.getId()).orElseThrow().getDestinationAttempts()).isEqualTo(3);
        assertThat(articles.findById(article.getId()).orElseThrow().getDestinationsAt()).isNull();
    }

    @Test
    @DisplayName("세 번 실패해 포기한 기사도 지시문 판이 바뀌면 다시 고른다")
    void aNewPromptVersionRetriesAnArticleThatGaveUpUnderTheOldOne() {
        // given
        // 이슈 #148: 예전 판에서 세 번 실패한 기사가 새 판으로는 한 번도 시도되지 않았다
        NewsArticle article = article("이상한 답", "2026-11-12T01:00:00Z", true, 0);
        FakeTagger old = new FakeTagger(Map.of("이상한 답", Optional.empty()));
        for (int i = 0; i < NewsTagger.MAX_ATTEMPTS + 1; i++) {
            tagger(old).tag();
        }
        assertThat(old.asked).hasSize(NewsTagger.MAX_ATTEMPTS);

        FakeTagger next = new FakeTagger(Map.of());
        next.version = "fake/d2";

        // when // then
        assertThat(tagger(next).tag()).isEqualTo(1);

        NewsArticle saved = articles.findById(article.getId()).orElseThrow();
        assertThat(saved.getDestinationsModel()).isEqualTo("fake/d2");
        assertThat(saved.getDestinationsAt()).isNotNull();
        assertThat(saved.getDestinationAttempts()).isZero();
    }

    @Test
    @DisplayName("새 판의 답을 읽지 못하면 세 번만 해 보고 옛 판의 구단을 남긴다")
    void anArticleTaggedBeforeKeepsItsTeamsWhenTheNewVersionCannotReadIt() {
        // given
        NewsArticle article = article("메츠행 급부상", "2026-11-12T01:00:00Z", true, 0);
        tagger(new FakeTagger(Map.of("메츠행 급부상",
                Optional.of(List.of(new DestinationTagger.Destination("NYM", Stance.LINKED)))))).tag();

        FakeTagger next = new FakeTagger(Map.of("메츠행 급부상", Optional.empty()));
        next.version = "fake/d2";

        // when
        for (int i = 0; i < NewsTagger.MAX_ATTEMPTS + 1; i++) {
            tagger(next).tag();
        }

        // then
        // 새 판으로 세 번까지만 다시 해 보고, 옛 판의 구단은 그대로 둔다
        assertThat(next.asked).hasSize(NewsTagger.MAX_ATTEMPTS);
        NewsArticle saved = articles.findById(article.getId()).orElseThrow();
        assertThat(saved.getDestinationsModel()).isEqualTo("fake/d1");
        assertThat(destinations.findMentions("노시환", Instant.parse("2026-11-01T00:00:00Z"), java.util.Set.of("마이데일리")))
                .extracting(DestinationMention::team).containsExactly("NYM");
    }

    @Test
    @DisplayName("구단을 고르는 서비스에 오류가 나면 시도 횟수로 세지 않는다")
    void aServiceFailureStopsTheRunWithoutCountingAnAttempt() {
        // given
        NewsArticle article = article("메츠행 급부상", "2026-11-12T01:00:00Z", true, 0);
        FakeTagger fake = new FakeTagger(Map.of());
        fake.failure = new Summarizer.Failure("overloaded", null);

        // when // then
        assertThat(tagger(fake).tag()).isZero();

        assertThat(articles.findById(article.getId()).orElseThrow().getDestinationAttempts()).isZero();
    }

    @Test
    @DisplayName("샘플 모드이거나 구단 고르기를 꺼 두면 구단을 고르지 않는다")
    void nothingInSampleModeOrWhenTheTaggerIsOff() {
        // given
        article("메츠행 급부상", "2026-11-12T01:00:00Z", true, 0);
        FakeTagger fake = new FakeTagger(Map.of());

        // when // then
        assertThat(new NewsTagger(articles, destinations, fake, POSTINGS, DataMode.SAMPLE, new MutableClock(NOW),
                transactions).tag()).isZero();
        assertThat(new NewsTagger(articles, destinations, DestinationTagger.OFF, POSTINGS, DataMode.MLB,
                new MutableClock(NOW), transactions).tag()).isZero();
        assertThat(fake.asked).isEmpty();
    }

    @Test
    @DisplayName("포스팅 선수 기사를 먼저 고르고 MLB 선수 기사는 MLB 지시문으로 고른다")
    void aPostingPlayersArticleUsesThePostingPromptAndOthersTheMlbPrompt() {
        // given
        // 이슈 #139: 포스팅 선수와 MLB 선수는 '주인공' 기준이 달라 지시문·판이 다르다. 포스팅 선수 기사를 먼저 고른다
        NewsArticle roster = article("이정후", "이정후의 새 조력자 등장", "2026-11-12T02:00:00Z", true, 0);
        NewsArticle posting = article("메츠행 급부상", "2026-11-12T01:00:00Z", true, 0);
        FakeTagger fake = new FakeTagger(Map.of());

        // when // then
        assertThat(tagger(fake).tag()).isEqualTo(2);

        assertThat(fake.asked).containsExactly("메츠행 급부상", "이정후의 새 조력자 등장");
        assertThat(fake.kinds).containsExactly(Kind.POSTING, Kind.MLB);
        assertThat(articles.findById(posting.getId()).orElseThrow().getDestinationsModel()).isEqualTo("fake/d1");
        assertThat(articles.findById(roster.getId()).orElseThrow().getDestinationsModel()).isEqualTo("fake/m1");
    }

    @Test
    @DisplayName("MLB 선수 지시문 판만 바뀌면 MLB 선수 기사만 다시 고른다")
    void aNewMlbPromptVersionRetagsOnlyTheMlbPlayersArticles() {
        // given
        article("이정후", "이정후의 새 조력자 등장", "2026-11-12T02:00:00Z", true, 0);
        article("메츠행 급부상", "2026-11-12T01:00:00Z", true, 0);
        tagger(new FakeTagger(Map.of())).tag();

        FakeTagger second = new FakeTagger(Map.of());
        second.mlbVersion = "fake/m2";

        // when // then
        assertThat(tagger(second).tag()).isEqualTo(1);

        assertThat(second.asked).containsExactly("이정후의 새 조력자 등장");
    }

    @Test
    @DisplayName("포스팅 선수가 없으면 MLB 지시문으로 구단을 고른다")
    void withoutAPostingPlayerEveryArticleUsesTheMlbPrompt() {
        // given
        article("이정후", "이정후의 새 조력자 등장", "2026-11-12T02:00:00Z", true, 0);
        FakeTagger fake = new FakeTagger(Map.of());
        Postings none = new Postings(new PostingProperties(List.of(), List.of()));

        // when
        new NewsTagger(articles, destinations, fake, none, DataMode.MLB, new MutableClock(NOW), transactions).tag();

        // then
        assertThat(fake.kinds).containsExactly(Kind.MLB);
    }

    private NewsArticle article(String title, String publishedAt, boolean summarized, int failedSummaries) {
        return article("노시환", title, publishedAt, summarized, failedSummaries);
    }

    private NewsArticle article(String subject, String title, String publishedAt, boolean summarized, int failedSummaries) {
        NewsArticle a = new NewsArticle(subject, "https://news.example.kr/" + title.hashCode(), "h" + title.hashCode(), title,
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
