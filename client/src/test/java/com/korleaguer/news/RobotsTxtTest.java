package com.korleaguer.news;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** robots.txt 판정(RFC 9309): 그룹 고르기, 가장 긴 규칙, '*'와 '$', crawl-delay */
class RobotsTxtTest {

    @Test
    void starGroupAppliesWhenNoGroupNamesUs() {
        RobotsTxt robots = RobotsTxt.parse("""
                User-agent: *
                Disallow: /search
                Allow: /
                """);

        assertThat(robots.allows("korleaguer", "/RSS/sports.xml")).isTrue();
        assertThat(robots.allows("korleaguer", "/search?q=roh")).isFalse();
    }

    @Test
    void namedGroupReplacesStarGroupAndNamesIgnoreCase() {
        RobotsTxt robots = RobotsTxt.parse("""
                User-agent: *
                Disallow:

                User-agent: ClaudeBot
                Disallow: /
                """);

        assertThat(robots.allows("korleaguer", "/feed")).isTrue();
        assertThat(robots.allows("ClaudeBot", "/feed")).isFalse();
        assertThat(robots.allows("claudebot", "/feed")).isFalse();
    }

    @Test
    void consecutiveUserAgentLinesShareOneGroup() {
        RobotsTxt robots = RobotsTxt.parse("""
                User-agent: GPTBot
                User-agent: anthropic-ai
                Disallow: /

                User-agent: *
                Allow: /
                """);

        assertThat(robots.allows("anthropic-ai", "/news")).isFalse();
        assertThat(robots.allows("GPTBot", "/news")).isFalse();
        assertThat(robots.allows("korleaguer", "/news")).isTrue();
    }

    @Test
    void longestRuleWinsAndAllowWinsATie() {
        RobotsTxt robots = RobotsTxt.parse("""
                User-agent: *
                Disallow: /news/
                Allow: /news/rss
                Disallow: /same
                Allow: /same
                """);

        assertThat(robots.allows("korleaguer", "/news/rss/sports.xml")).isTrue();
        assertThat(robots.allows("korleaguer", "/news/article/1")).isFalse();
        assertThat(robots.allows("korleaguer", "/same")).isTrue();
    }

    @Test
    void wildcardMatchesAnythingAndDollarAnchorsTheEnd() {
        RobotsTxt robots = RobotsTxt.parse("""
                User-agent: *
                Disallow: /*?s
                Disallow: /*.php$
                Disallow: /*/email
                """);

        assertThat(robots.allows("korleaguer", "/?s=roh")).isFalse();
        assertThat(robots.allows("korleaguer", "/2026/11/post.html?s=1")).isFalse();
        assertThat(robots.allows("korleaguer", "/index.php")).isFalse();
        assertThat(robots.allows("korleaguer", "/index.php?page=2")).isTrue();
        assertThat(robots.allows("korleaguer", "/2026/11/post.html/email")).isFalse();
        assertThat(robots.allows("korleaguer", "/players/si-hwan-roh/feed")).isTrue();
    }

    @Test
    void emptyFileAndEmptyDisallowAllowEverything() {
        assertThat(RobotsTxt.parse("").allows("korleaguer", "/anything")).isTrue();
        assertThat(RobotsTxt.parse("User-agent: *\nDisallow:\n").allows("korleaguer", "/anything")).isTrue();
    }

    @Test
    void rulesBeforeAnyUserAgentAreIgnored() {
        RobotsTxt robots = RobotsTxt.parse("""
                Disallow: /
                User-agent: *
                Allow: /
                """);

        assertThat(robots.allows("korleaguer", "/feed")).isTrue();
    }

    @Test
    void commentsAndVersionInAgentNameAreIgnored() {
        RobotsTxt robots = RobotsTxt.parse("""
                # 우리 사이트 규칙
                User-agent: korleaguer/0.1 (+https://example.com) # 이름만 본다
                Disallow: /private # 막음
                """);

        assertThat(robots.allows("korleaguer", "/private/feed")).isFalse();
        assertThat(robots.allows("korleaguer", "/public/feed")).isTrue();
    }

    @Test
    void twoStarGroupsMergeAndCrawlDelayIsKept() {
        // MLB Trade Rumors처럼 '*' 그룹이 두 번 나오고 두 번째에 Crawl-delay만 있는 경우
        RobotsTxt robots = RobotsTxt.parse("""
                User-agent: *
                Disallow: /search
                Sitemap: https://example.com/sitemap.xml

                User-agent: *
                Crawl-delay: 1
                """);

        assertThat(robots.allows("korleaguer", "/search")).isFalse();
        assertThat(robots.allows("korleaguer", "/feed")).isTrue();
        assertThat(robots.crawlDelay("korleaguer")).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void crawlDelayLineDoesNotEndTheGroup() {
        RobotsTxt robots = RobotsTxt.parse("""
                User-agent: *
                Crawl-delay: 5
                Disallow: /article/search
                """);

        assertThat(robots.crawlDelay("korleaguer")).isEqualTo(Duration.ofSeconds(5));
        assertThat(robots.allows("korleaguer", "/article/search?q=1")).isFalse();
    }

    @Test
    void disallowAllBlocksEverythingButRobotsTxt() {
        assertThat(RobotsTxt.DISALLOW_ALL.allows("korleaguer", "/feed")).isFalse();
        assertThat(RobotsTxt.DISALLOW_ALL.allows("korleaguer", "/robots.txt")).isTrue();
        assertThat(RobotsTxt.ALLOW_ALL.allows("korleaguer", "/feed")).isTrue();
    }
}
