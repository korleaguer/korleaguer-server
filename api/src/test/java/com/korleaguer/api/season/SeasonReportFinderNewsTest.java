package com.korleaguer.api.season;

import com.korleaguer.api.article.LinkedTeamsCounter;

import com.korleaguer.api.article.PostingSummary;

import com.korleaguer.api.player.RosterMember;

import com.korleaguer.config.NewsProperties;
import com.korleaguer.domain.AppearanceRepository;
import com.korleaguer.domain.DestinationMention;
import com.korleaguer.domain.MinorGameLogRepository;
import com.korleaguer.domain.NewsArticle;
import com.korleaguer.domain.NewsArticleRepository;
import com.korleaguer.domain.NewsDestination;
import com.korleaguer.domain.NewsDestinationRepository;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerLeaguePositionRepository;
import com.korleaguer.domain.PlayerMoveRepository;
import com.korleaguer.domain.PlayerSeasonMetricRepository;
import com.korleaguer.domain.SeasonDatesRepository;
import com.korleaguer.domain.Team;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 성적표 '이번 겨울' 기사: 시즌이 끝난 뒤 기사만, 지금 수집하는 언론사만, 같은 기사는 한 줄 (기사 내용은 지어낸 값) */
class SeasonReportFinderNewsTest {

    private static final Instant NOW = Instant.parse("2026-11-13T03:00:00Z");

    private final NewsArticleRepository news = mock(NewsArticleRepository.class);
    private final NewsDestinationRepository destinations = mock(NewsDestinationRepository.class);
    private static final NewsProperties NEWS_PROPERTIES = new NewsProperties(true, "0 */30 * * * *",
            "korleaguer/test", List.of("korleaguer"), List.of(new NewsProperties.Source("마이데일리", "https://a.example/rss",
            null, null, null, "lead"), new NewsProperties.Source("스포츠조선", "https://b.example/rss", null, null, null,
            "lead")));
    private final SeasonReportFinder finder = new SeasonReportFinder(mock(AppearanceRepository.class),
            mock(MinorGameLogRepository.class), mock(PlayerMoveRepository.class), mock(PlayerSeasonMetricRepository.class),
            mock(PlayerLeaguePositionRepository.class), mock(SeasonDatesRepository.class), news,
            new LinkedTeamsCounter(news, destinations, NEWS_PROPERTIES), NEWS_PROPERTIES, mock(LeagueEnvironments.class), Clock.fixed(NOW, ZoneOffset.UTC),
            // 단위 시험이라 따로 읽는 트랜잭션은 흉내만 낸다(트랜잭션 관리자 mock)
            new OptionalReads(mock(PlatformTransactionManager.class)));

    @Test
    @DisplayName("이번 겨울 기사는 시즌 뒤 지금 모으는 언론사 것만, 같은 기사는 한 줄로 준다")
    void onlyWinterArticlesFromCurrentPublishersOneLinePerStory() {
        // given
        when(news.findLatest(eq("김하성"), any())).thenReturn(List.of(
                article("https://a.example/2", "김하성 필라델피아행?", "마이데일리", "2026-11-11T00:00:00Z"),
                article("https://b.example/2", "김하성 필라델피아행?", "스포츠조선", "2026-11-11T01:00:00Z"),
                article("https://c.example/1", "김하성 FA 소식", "뺀 언론사", "2026-11-10T00:00:00Z"),
                article("https://a.example/1", "김하성 시즌 마지막 경기", "마이데일리", "2026-09-27T20:00:00Z")));

        // when
        List<PostingSummary.Headline> headlines = finder.winterNews("김하성", Instant.parse("2026-09-29T04:00:00Z"));

        // then
        assertThat(headlines).extracting(PostingSummary.Headline::url).containsExactly("https://a.example/2");
        assertThat(headlines.getFirst().alsoBy()).containsExactly("스포츠조선");
    }

    @Test
    @DisplayName("선수 페이지 기사는 시즌 중 것도 넣고 뺀 언론사는 빼며 정한 개수까지만 준다")
    void playerPageNewsUsesTheSameRulesWithoutTheWinterCutAndAtMostLimit() {
        // given
        when(news.findLatest(eq("김하성"), any())).thenReturn(List.of(
                article("https://a.example/3", "김하성 FA 선언", "마이데일리", "2026-11-12T00:00:00Z"),
                article("https://a.example/2", "김하성 필라델피아행?", "마이데일리", "2026-11-11T00:00:00Z"),
                article("https://c.example/1", "김하성 FA 소식", "뺀 언론사", "2026-11-10T00:00:00Z"),
                article("https://a.example/1", "김하성 시즌 마지막 경기", "마이데일리", "2026-09-27T20:00:00Z")));

        // when // then
        assertThat(finder.news("김하성", Instant.EPOCH, 10)).extracting(PostingSummary.Headline::url)
                .containsExactly("https://a.example/3", "https://a.example/2", "https://a.example/1");
        assertThat(finder.news("김하성", Instant.EPOCH, 2)).hasSize(2);
    }

    @Test
    @DisplayName("선수 이름이 없으면 이번 겨울 기사가 없다")
    void noNameMeansNoArticles() {
        // when // then
        assertThat(finder.winterNews(null, Instant.EPOCH)).isEmpty();
    }

    @Test
    @DisplayName("같은 언론사 기사가 이어지면 하루 안의 다른 언론사 기사를 앞으로 당긴다")
    void winterNewsDoesNotPutOnePublisherTwiceInARowWhenAnotherIsWithinADay() {
        // when
        // 이슈 #140: 김하성 기사 대부분이 한 언론사라 '이번 겨울' 앞 두 건이 한 언론사로 찼다
        List<PostingSummary.Headline> spread = SeasonReportFinder.spreadPublishers(List.of(
                headline("a1", "마이데일리", "2026-11-12T10:00:00Z"),
                headline("a2", "마이데일리", "2026-11-12T08:00:00Z"),
                headline("b1", "스포츠조선", "2026-11-12T01:00:00Z"),
                headline("a3", "마이데일리", "2026-11-11T09:00:00Z"),
                headline("a4", "마이데일리", "2026-11-09T09:00:00Z"),
                headline("b2", "스포츠조선", "2026-11-05T09:00:00Z")));

        // then
        // b1은 a2보다 하루 안이라 앞으로 오고, b2는 a4보다 나흘 늦어 제자리(늦은 순을 크게 흐트리지 않는다)
        assertThat(spread).extracting(PostingSummary.Headline::url)
                .containsExactly("a1", "b1", "a2", "a3", "a4", "b2");
    }

    @Test
    @DisplayName("FA 선수의 거론 구단은 거론이 많은 순으로 나오고 직전 구단은 빠진다")
    void aFreeAgentGetsTheTeamsLinkedToHimButNotHisLastTeam() {
        // given
        // 이슈 #140: 김하성(FA, 직전 애틀랜타)의 기사에서 AI가 고른 구단. 애틀랜타는 세지 않는다
        when(destinations.findMentions(eq("김하성"), any(), any())).thenReturn(List.of(
                new DestinationMention(1L, "PHI", NewsDestination.Stance.LINKED, Instant.parse("2026-11-12T01:00:00Z")),
                new DestinationMention(2L, "PHI", NewsDestination.Stance.LINKED, Instant.parse("2026-11-11T01:00:00Z")),
                new DestinationMention(3L, "SEA", NewsDestination.Stance.LINKED, Instant.parse("2026-11-12T02:00:00Z")),
                new DestinationMention(4L, "ATL", NewsDestination.Stance.DOUBTED, Instant.parse("2026-11-10T01:00:00Z"))));
        when(news.findLatest(eq("김하성"), any())).thenReturn(List.of());

        // when
        PostingSummary.LinkedTeams linked = finder.freeAgentLinkedTeams(member("김하성", "ATL", true), NOW);

        // then
        assertThat(linked.teams()).extracting(PostingSummary.LinkedTeam::abbreviation, PostingSummary.LinkedTeam::linked)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("PHI", 2), org.assertj.core.groups.Tuple.tuple("SEA", 1));
    }

    @Test
    @DisplayName("소속팀이 있거나 반대 전망 기사뿐이면 거론 구단을 보이지 않는다")
    void noLinkedTeamsForAPlayerWithATeamOrWithoutAnyLinkedMention() {
        // given
        when(destinations.findMentions(eq("김하성"), any(), any())).thenReturn(List.of(
                new DestinationMention(4L, "NYY", NewsDestination.Stance.DOUBTED, Instant.parse("2026-11-10T01:00:00Z"))));
        when(news.findLatest(any(), any())).thenReturn(List.of());

        // when // then
        assertThat(finder.freeAgentLinkedTeams(member("이정후", "SF", false), NOW)).isNull();
        // 반대 전망뿐이면 보일 것이 없다
        assertThat(finder.freeAgentLinkedTeams(member("김하성", "ATL", true), NOW)).isNull();
    }

    private static RosterMember member(String name, String lastTeam, boolean ended) {
        Player player = mock(Player.class);
        when(player.getNameKo()).thenReturn(name);
        Team team = mock(Team.class);
        when(team.getAbbreviation()).thenReturn(lastTeam);
        return new RosterMember(player, team, ended);
    }

    private static PostingSummary.Headline headline(String url, String publisher, String publishedAt) {
        return new PostingSummary.Headline(url, url, publisher, Instant.parse(publishedAt));
    }

    private static NewsArticle article(String url, String title, String publisher, String publishedAt) {
        return new NewsArticle("김하성", url, url, title, publisher, Instant.parse(publishedAt), Instant.parse(publishedAt));
    }
}
