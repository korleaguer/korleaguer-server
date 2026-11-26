package com.korleaguer.news;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** robots.txt 판정(RFC 9309): 그룹 고르기, 가장 긴 규칙, '*'와 '$', crawl-delay */
class RobotsTxtTest {

    @Test
    @DisplayName("우리 이름으로 된 규칙이 없으면 모두에게 적용되는 규칙을 따른다")
    void starGroupAppliesWhenNoGroupNamesUs() {
        // given
        RobotsTxt robots = RobotsTxt.parse("""
                User-agent: *
                Disallow: /search
                Allow: /
                """);

        // when // then
        assertThat(robots.allows("korleaguer", "/RSS/sports.xml")).isTrue();
        assertThat(robots.allows("korleaguer", "/search?q=roh")).isFalse();
    }

    @Test
    @DisplayName("이름을 콕 집은 규칙은 공통 규칙 대신 쓰고, 이름의 대소문자는 따지지 않는다")
    void namedGroupReplacesStarGroupAndNamesIgnoreCase() {
        // given
        RobotsTxt robots = RobotsTxt.parse("""
                User-agent: *
                Disallow:

                User-agent: ClaudeBot
                Disallow: /
                """);

        // when // then
        assertThat(robots.allows("korleaguer", "/feed")).isTrue();
        assertThat(robots.allows("ClaudeBot", "/feed")).isFalse();
        assertThat(robots.allows("claudebot", "/feed")).isFalse();
    }

    @Test
    @DisplayName("수집기 이름이 잇달아 적히면 뒤에 나오는 규칙을 함께 적용받는다")
    void consecutiveUserAgentLinesShareOneGroup() {
        // given
        RobotsTxt robots = RobotsTxt.parse("""
                User-agent: GPTBot
                User-agent: anthropic-ai
                Disallow: /

                User-agent: *
                Allow: /
                """);

        // when // then
        assertThat(robots.allows("anthropic-ai", "/news")).isFalse();
        assertThat(robots.allows("GPTBot", "/news")).isFalse();
        assertThat(robots.allows("korleaguer", "/news")).isTrue();
    }

    @Test
    @DisplayName("주소에 더 길게 맞는 규칙이 이기고, 길이가 같으면 허용이 이긴다")
    void longestRuleWinsAndAllowWinsATie() {
        // given
        RobotsTxt robots = RobotsTxt.parse("""
                User-agent: *
                Disallow: /news/
                Allow: /news/rss
                Disallow: /same
                Allow: /same
                """);

        // when // then
        assertThat(robots.allows("korleaguer", "/news/rss/sports.xml")).isTrue();
        assertThat(robots.allows("korleaguer", "/news/article/1")).isFalse();
        assertThat(robots.allows("korleaguer", "/same")).isTrue();
    }

    @Test
    @DisplayName("별표는 어떤 글자에나 맞고, 달러 표시는 주소 끝에서만 맞는다")
    void wildcardMatchesAnythingAndDollarAnchorsTheEnd() {
        // given
        RobotsTxt robots = RobotsTxt.parse("""
                User-agent: *
                Disallow: /*?s
                Disallow: /*.php$
                Disallow: /*/email
                """);

        // when // then
        assertThat(robots.allows("korleaguer", "/?s=roh")).isFalse();
        assertThat(robots.allows("korleaguer", "/2026/11/post.html?s=1")).isFalse();
        assertThat(robots.allows("korleaguer", "/index.php")).isFalse();
        assertThat(robots.allows("korleaguer", "/index.php?page=2")).isTrue();
        assertThat(robots.allows("korleaguer", "/2026/11/post.html/email")).isFalse();
        assertThat(robots.allows("korleaguer", "/players/si-hwan-roh/feed")).isTrue();
    }

    @Test
    @DisplayName("끝에 별표를 붙인 막기 규칙은 그 앞까지 같은 주소를 모두 막는다")
    void trailingWildcardBlocksEverythingAfterThePrefix() {
        // given
        RobotsTxt robots = RobotsTxt.parse("""
                User-agent: *
                Disallow: /private*
                Disallow: /tmp**
                """);

        // when // then
        assertThat(robots.allows("korleaguer", "/private")).isFalse();
        assertThat(robots.allows("korleaguer", "/private/feed")).isFalse();
        assertThat(robots.allows("korleaguer", "/tmp/a.xml")).isFalse();
        assertThat(robots.allows("korleaguer", "/public/feed")).isTrue();
    }

    @Test
    @DisplayName("규칙 파일이 비었거나 막을 곳을 비워 두면 모든 주소를 허용한다")
    void emptyFileAndEmptyDisallowAllowEverything() {
        // when // then
        assertThat(RobotsTxt.parse("").allows("korleaguer", "/anything")).isTrue();
        assertThat(RobotsTxt.parse("User-agent: *\nDisallow:\n").allows("korleaguer", "/anything")).isTrue();
    }

    @Test
    @DisplayName("수집기 이름보다 먼저 나온 규칙은 무시한다")
    void rulesBeforeAnyUserAgentAreIgnored() {
        // given
        RobotsTxt robots = RobotsTxt.parse("""
                Disallow: /
                User-agent: *
                Allow: /
                """);

        // when // then
        assertThat(robots.allows("korleaguer", "/feed")).isTrue();
    }

    @Test
    @DisplayName("수집기 이름 뒤의 버전과 주석은 빼고 규칙을 읽는다")
    void commentsAndVersionInAgentNameAreIgnored() {
        // given
        RobotsTxt robots = RobotsTxt.parse("""
                # 우리 사이트 규칙
                User-agent: korleaguer/0.1 (+https://example.com) # 이름만 본다
                Disallow: /private # 막음
                """);

        // when // then
        assertThat(robots.allows("korleaguer", "/private/feed")).isFalse();
        assertThat(robots.allows("korleaguer", "/public/feed")).isTrue();
    }

    @Test
    @DisplayName("공통 규칙이 두 번 나오면 합치고 수집 간격도 남긴다")
    void twoStarGroupsMergeAndCrawlDelayIsKept() {
        // given
        // MLB Trade Rumors처럼 '*' 그룹이 두 번 나오고 두 번째에 Crawl-delay만 있는 경우
        RobotsTxt robots = RobotsTxt.parse("""
                User-agent: *
                Disallow: /search
                Sitemap: https://example.com/sitemap.xml

                User-agent: *
                Crawl-delay: 1
                """);

        // when // then
        assertThat(robots.allows("korleaguer", "/search")).isFalse();
        assertThat(robots.allows("korleaguer", "/feed")).isTrue();
        assertThat(robots.crawlDelay("korleaguer")).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("수집 간격 줄 다음에 나오는 막기 규칙도 같은 묶음으로 적용한다")
    void crawlDelayLineDoesNotEndTheGroup() {
        // given
        RobotsTxt robots = RobotsTxt.parse("""
                User-agent: *
                Crawl-delay: 5
                Disallow: /article/search
                """);

        // when // then
        assertThat(robots.crawlDelay("korleaguer")).isEqualTo(Duration.ofSeconds(5));
        assertThat(robots.allows("korleaguer", "/article/search?q=1")).isFalse();
    }

    @Test
    @DisplayName("모두 막을 때도 robots.txt는 허용하고, 모두 허용할 때는 피드 주소를 허용한다")
    void disallowAllBlocksEverythingButRobotsTxt() {
        // when // then
        assertThat(RobotsTxt.DISALLOW_ALL.allows("korleaguer", "/feed")).isFalse();
        assertThat(RobotsTxt.DISALLOW_ALL.allows("korleaguer", "/robots.txt")).isTrue();
        assertThat(RobotsTxt.ALLOW_ALL.allows("korleaguer", "/feed")).isTrue();
    }
}
