package com.korleaguer.api;

import com.korleaguer.config.AppConfig;
import com.korleaguer.ingest.CatchUpFinished;
import com.korleaguer.ingest.IngestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.SimpleKey;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 따라잡기가 끝나면(CatchUpFinished) 홈·선수 페이지 전부·비시즌 홈의 선수 카드가 캐시에 미리 들어가고,
 * 내 선수 응답도 캐시했다가 수집이 끝나면 비운다. 샘플 데이터, 지금을 2026-11-01(비시즌)로 고정한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class CacheWarmerTest {

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private BaseballQueryService queryService;

    @Autowired
    private MyPlayersService myPlayers;

    @Autowired
    private IngestService ingestService;

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-11-01T11:00:00Z"), ZoneOffset.UTC);
        }
    }

    @BeforeEach
    void emptyCaches() {
        cacheManager.getCacheNames().forEach(name -> cache(name).clear());
    }

    @Test
    void catchUpFinishedFillsHomeEveryPlayerAndOffSeasonCards() {
        events.publishEvent(new CatchUpFinished());

        assertThat(cache(AppConfig.HOME_CACHE).get(SimpleKey.EMPTY)).isNotNull();
        List<PlayerSummary> players = queryService.players();
        assertThat(players).isNotEmpty();
        for (PlayerSummary player : players) {
            assertThat(cache(AppConfig.PLAYER_CACHE).get(player.id())).as("player %d", player.id()).isNotNull();
        }

        // 비시즌 홈(웹)이 부르는 그대로: 명단 전원을 홈 응답의 순서대로, 7일
        HomeResponse home = queryService.home();
        assertThat(home.offseason()).isNotNull();
        String ids = home.offseason().playerIds().stream().map(String::valueOf).collect(Collectors.joining(","));
        assertThat(cache(AppConfig.MY_CACHE).get(ids + "|7")).isNotNull();
    }

    @Test
    void myPlayersIsCachedUntilIngestFinishes() {
        MyPlayersResponse first = myPlayers.find("1,2", 7);
        assertThat(myPlayers.find("1,2", 7)).isSameAs(first);
        // 기간이 다르면 따로 둔다
        assertThat(myPlayers.find("1,2", 14)).isNotSameAs(first);

        ingestService.catchUp(false);

        assertThat(cache(AppConfig.MY_CACHE).get("1,2|7")).isNull();
        assertThat(myPlayers.find("1,2", 7)).isNotSameAs(first);
    }

    private Cache cache(String name) {
        return cacheManager.getCache(name);
    }
}
