package com.korleaguer.api.article;

import com.korleaguer.domain.NewsArticle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 화면에 보일 기사: 지금 받는 언론사의 주인공 기사, 포스팅 선수면 영어 MLB 매체이거나 제목이 MLB 이야기인 국내 기사 */
class ShownNewsTest {

    private static final Instant AT = Instant.parse("2026-11-12T01:00:00Z");
    private static final Set<String> AI_PUBLISHERS = Set.of("MLB.com");

    @Test
    @DisplayName("국내 기사는 제목에 메이저리그 이야기가 있으면 보이고 KBO 이야기뿐이면 숨는다")
    void domesticArticleNeedsAnMlbTitle() {
        // given
        NewsArticle mlb = article("노시환, 메이저리그 포스팅 신청", "스포츠신문");
        NewsArticle kbo = article("KIA가 필요한 FA는 노시환급 초대형계약", "스포츠신문");

        // when // then
        assertThat(ShownNews.isMlbStory(mlb, AI_PUBLISHERS)).isTrue();
        assertThat(ShownNews.isMlbStory(kbo, AI_PUBLISHERS)).isFalse();
    }

    @Test
    @DisplayName("영어 메이저리그 매체 기사는 제목에 메이저리그 말이 없어도 보인다")
    void aiPublisherArticleIsAlwaysAnMlbStory() {
        // given
        NewsArticle article = article("노시환 계약 소식", "MLB.com");

        // when // then
        assertThat(ShownNews.isMlbStory(article, AI_PUBLISHERS)).isTrue();
    }

    @Test
    @DisplayName("지금 받는 언론사의 기사는 보이고, 받지 않게 된 언론사의 기사는 숨는다")
    void onlyCurrentPublishersAreShown() {
        // given
        Set<String> publishers = Set.of("스포츠신문");
        NewsArticle current = article("노시환 포스팅 신청", "스포츠신문");
        NewsArticle dropped = article("노시환 포스팅 신청", "그만 받는 신문");

        // when // then
        assertThat(ShownNews.isShown(current, publishers)).isTrue();
        assertThat(ShownNews.isShown(dropped, publishers)).isFalse();
    }

    @Test
    @DisplayName("이 선수가 주인공이 아니라고 본 기사는 숨고, 아직 판단하지 않은 기사는 보인다")
    void offTopicArticleIsHidden() {
        // given
        Set<String> publishers = Set.of("스포츠신문");
        NewsArticle offTopic = article("다른 선수 이야기 속 노시환", "스포츠신문");
        offTopic.destinationsTagged("model", AT, true);
        NewsArticle notJudged = article("노시환 포스팅 신청", "스포츠신문");

        // when // then
        assertThat(ShownNews.isShown(offTopic, publishers)).isFalse();
        assertThat(ShownNews.isShown(notJudged, publishers)).isTrue();
    }

    private static NewsArticle article(String title, String publisher) {
        return new NewsArticle("노시환", "https://example.com/" + title.hashCode(), "hash", title, publisher, AT, AT);
    }
}
