package com.korleaguer.api;

import com.korleaguer.config.AppConfig;
import com.korleaguer.config.ResponseCaches;
import com.korleaguer.ingest.CatchUpFinished;
import com.korleaguer.ingest.IngestService;
import com.korleaguer.support.MutableClock;
import jakarta.persistence.EntityManager;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 응답 캐시는 보이는 것이 실제로 바뀔 때만 비우고, 따라잡기가 끝나면 빠진 것을 채운다.
 * 샘플 데이터, 지금을 2026-11-01 11:00Z(한국 20:00, 비시즌)에서 시작한다.
 * 테스트에서는 자동 수집이 꺼져 있어, 비운 뒤 뒤에서 다시 채우지 않는다(채우기는 따라잡기 끝에서만 확인한다).
 */
@SpringBootTest
@ActiveProfiles("test")
class CacheWarmerTest {

    private static final Instant START = Instant.parse("2026-11-01T11:00:00Z");

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private ResponseCaches responseCaches;

    @Autowired
    private BaseballQueryService queryService;

    @Autowired
    private MyPlayersService myPlayers;

    @Autowired
    private IngestService ingestService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private MutableClock clock;

    @TestConfiguration
    static class Clocks {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(START);
        }
    }

    @BeforeEach
    void startFresh() {
        clock.set(START);
        // 앞선 테스트의 쓰기는 본 것으로 치고, 캐시를 비우고, 시각 키를 지금으로 맞춘다
        responseCaches.clearAll("테스트 시작");
        events.publishEvent(new CatchUpFinished());
    }

    @Test
    void catchUpFinishedFillsHomeEveryPlayerAndOffSeasonCards() {
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
    void anyCombinationIsAssembledFromCachedCards() {
        // 따라잡기 끝(startFresh)에서 명단 전원의 카드를 7·14·30일마다 채워 둔다
        for (PlayerSummary player : queryService.players()) {
            for (int days : List.of(7, 14, 30)) {
                assertThat(cache(AppConfig.CARD_CACHE).get(player.id() + "|" + days))
                        .as("card %d|%d", player.id(), days).isNotNull();
            }
        }

        // 처음 보는 조합(순서도 바꿈)은 그 카드들을 요청한 순서대로 모은 것이다
        MyPlayersResponse combo = myPlayers.find("3,1", 14);
        assertThat(combo.players()).hasSize(2);
        assertThat(combo.players().get(0)).isSameAs(myPlayers.card(3, 14).getFirst());
        assertThat(combo.players().get(1)).isSameAs(myPlayers.card(1, 14).getFirst());
        // 없는 선수는 빈 카드로 두고 건너뛴다
        assertThat(myPlayers.card(999, 14)).isEmpty();
        assertThat(myPlayers.find("999,3", 14).players()).containsExactly(combo.players().get(0));
    }

    @Test
    void cachesStayUntilTheDatabaseActuallyChanges() {
        MyPlayersResponse first = myPlayers.find("1,2", 7);
        assertThat(myPlayers.find("1,2", 7)).isSameAs(first);
        // 기간이 다르면 따로 둔다
        assertThat(myPlayers.find("1,2", 14)).isNotSameAs(first);

        // 아무것도 쓰지 않았으면 그대로 둔다(12분마다 따라잡기가 할 일 없이 끝나는 경우)
        assertThat(responseCaches.clearIfDataChanged("확인")).isFalse();
        events.publishEvent(new CatchUpFinished());
        assertThat(myPlayers.find("1,2", 7)).isSameAs(first);

        // 응답에 나오는 표에 쓰면(Hibernate가 보내는 UPDATE) 다음 확인 때 비운다. 실제 행은 바꾸지 않는다.
        transactions.executeWithoutResult(status ->
                entityManager.createNativeQuery("update team set name_en = name_en where 1 = 0").executeUpdate());
        events.publishEvent(new CatchUpFinished());
        assertThat(myPlayers.find("1,2", 7)).isNotSameAs(first);
    }

    @Test
    void ingestClearsCachesWhenSomethingWasWritten() {
        MyPlayersResponse first = myPlayers.find("1,2", 7);
        transactions.executeWithoutResult(status ->
                entityManager.createNativeQuery("update team set name_en = name_en where 1 = 0").executeUpdate());

        // 수집이 끝나면 그사이 쓴 것을 보고 바로 비운다(따라잡기를 기다리지 않는다)
        ingestService.catchUp(false);

        assertThat(cache(AppConfig.MY_CACHE).get("1,2|7")).isNull();
        assertThat(myPlayers.find("1,2", 7)).isNotSameAs(first);
    }

    @Test
    void newKoreanDateClearsCaches() {
        MyPlayersResponse first = myPlayers.find("1,2", 7);

        // 같은 날 한국 23:50: 그대로
        clock.set(Instant.parse("2026-11-01T14:50:00Z"));
        events.publishEvent(new CatchUpFinished());
        assertThat(myPlayers.find("1,2", 7)).isSameAs(first);

        // 한국 날짜가 11-02로 바뀌면 비우고, 따라잡기 끝에서 홈·선수를 다시 채운다
        clock.set(Instant.parse("2026-11-01T15:00:00Z"));
        events.publishEvent(new CatchUpFinished());
        assertThat(cache(AppConfig.MY_CACHE).get("1,2|7")).isNull();
        assertThat(cache(AppConfig.HOME_CACHE).get(SimpleKey.EMPTY)).isNotNull();
    }

    @Test
    void newHeadlineClearsOnlyHomeAndPlayerPages() {
        MyPlayersResponse my = myPlayers.find("1,2", 7);
        PlayerPageResponse player = queryService.player(1L);
        HomeResponse home = queryService.home();

        // 기사(news_article)는 홈과 선수 페이지(그 선수 기사, 이슈 #133)에만 나온다. 실제 행은 바꾸지 않는다.
        transactions.executeWithoutResult(status ->
                entityManager.createNativeQuery("update news_article set title = title where 1 = 0").executeUpdate());
        events.publishEvent(new CatchUpFinished());

        assertThat(queryService.home()).isNotSameAs(home);
        assertThat(queryService.player(1L)).isNotSameAs(player);
        assertThat(myPlayers.find("1,2", 7)).isSameAs(my);
    }

    @Test
    void postingStatusChangeDoesNotSwallowANewHeadline() {
        clock.set(Instant.parse("2026-11-02T12:50:00Z"));
        events.publishEvent(new CatchUpFinished());
        PlayerPageResponse player = queryService.player(1L);

        // 같은 따라잡기 사이에 기사도 쓰고 포스팅 상태도 바뀌었다: 포스팅 쪽이 홈만 지워도 기사 쪽이 선수 페이지를 지운다
        transactions.executeWithoutResult(status ->
                entityManager.createNativeQuery("update news_article set title = title where 1 = 0").executeUpdate());
        clock.set(Instant.parse("2026-11-02T13:10:00Z"));
        events.publishEvent(new CatchUpFinished());

        assertThat(queryService.player(1L)).isNotSameAs(player);
    }

    @Test
    void postingStatusChangeClearsOnlyHome() {
        // 노시환 협상 시작(미국 11/2 08:00 = 13:00Z, 한국 11/2 22:00) 10분 전
        clock.set(Instant.parse("2026-11-02T12:50:00Z"));
        events.publishEvent(new CatchUpFinished());
        MyPlayersResponse my = myPlayers.find("1,2", 7);
        HomeResponse home = queryService.home();

        // 같은 한국·미국 날짜에 협상이 시작됐다(UPCOMING → NEGOTIATING): 홈만 다시 만든다
        clock.set(Instant.parse("2026-11-02T13:10:00Z"));
        events.publishEvent(new CatchUpFinished());

        assertThat(queryService.home()).isNotSameAs(home);
        assertThat(myPlayers.find("1,2", 7)).isSameAs(my);
    }

    private Cache cache(String name) {
        return cacheManager.getCache(name);
    }
}
