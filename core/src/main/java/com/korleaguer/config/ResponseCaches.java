package com.korleaguer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 화면용 응답 캐시(홈·선수·내 선수·내 선수 카드·한국인 역대 기록·이동 연표·같은 경기·비교)를 지우는 곳. 데이터가 실제로 바뀌었을 때만, 바뀐 것이 나오는 캐시만 지운다.
 * <ul>
 *   <li>clearIfDataChanged: 지난번 확인 뒤 DB에 쓴 것(DataChanges)이 있으면 지운다. 모든 응답에 나오는 데이터면 모두,
 *       기사 표(homeVersion)뿐이면 기사가 나오는 홈·선수 페이지만(NEWS). 수집이 끝날 때(IngestService)와
 *       12분마다(CacheWarmer, 기사처럼 수집 밖에서 쓴 것) 부른다.</li>
 *   <li>clearAll·clearHome: 그냥 지운다. 시각으로 바뀌는 값(날짜는 모두, 포스팅 상태는 홈만)이 바뀌었을 때 부른다.</li>
 * </ul>
 * 지우면 ResponseCachesCleared를 보낸다(api.CacheWarmer가 다시 채운다).
 */
@Component
public class ResponseCaches {

    private static final Logger log = LoggerFactory.getLogger(ResponseCaches.class);

    public static final String HOME_CACHE = "home";
    public static final String PLAYER_CACHE = "player";
    /** 내 선수(GET /api/my). 선수 조합(ids)과 기간(days)마다 따로 둔다 */
    public static final String MY_CACHE = "my";
    /** 내 선수 카드 한 장. 선수와 기간(days)마다 따로 둔다. 처음 보는 조합도 이것을 모아 바로 답한다 */
    public static final String CARD_CACHE = "card";
    /** 한국인 메이저리거 역대 기록(GET /api/records, 이슈 #177). 응답이 하나뿐이다 */
    public static final String RECORDS_CACHE = "records";
    /** 선수 이동 연표(GET /api/players/timeline). 시즌마다 따로 둔다 */
    public static final String TIMELINE_CACHE = "timeline";
    /** 같은 경기에 나온 우리 선수(GET /api/players/{id}/same-games, 이슈 #217). 선수마다 따로 둔다 */
    public static final String SAME_GAMES_CACHE = "sameGames";
    /** 두 선수 비교용 타자 시즌 성적 묶음(GET /api/players/{id}/compare, 이슈 #221). 선수마다 따로 둔다 */
    public static final String COMPARE_CACHE = "compare";

    public static final List<String> ALL = List.of(HOME_CACHE, PLAYER_CACHE, MY_CACHE, CARD_CACHE, RECORDS_CACHE,
            TIMELINE_CACHE, SAME_GAMES_CACHE, COMPARE_CACHE);
    public static final List<String> HOME = List.of(HOME_CACHE);
    /** 기사가 나오는 응답: 홈(포스팅·성적표 기사)과 선수 페이지(그 선수 기사, 이슈 #133) */
    public static final List<String> NEWS = List.of(HOME_CACHE, PLAYER_CACHE);

    private final CacheManager cacheManager;
    private final ApplicationEventPublisher events;
    /** 마지막으로 지웠을 때(또는 확인했을 때)의 DataChanges.version()·homeVersion() */
    private long seenVersion = DataChanges.version();
    private long seenHomeVersion = DataChanges.homeVersion();

    public ResponseCaches(CacheManager cacheManager, ApplicationEventPublisher events) {
        this.cacheManager = cacheManager;
        this.events = events;
    }

    /** 지난번 확인 뒤 응답에 나오는 데이터를 DB에 썼으면 그 데이터가 나오는 캐시를 지운다. 지웠으면 true */
    public boolean clearIfDataChanged(String reason) {
        boolean all;
        boolean home;
        synchronized (this) {
            all = DataChanges.version() != seenVersion;
            home = DataChanges.homeVersion() != seenHomeVersion;
        }
        if (all) {
            clearAll(reason);
        } else if (home) {
            clearNews(reason + "(기사)");
        }
        return all || home;
    }

    /** 캐시를 모두 지우고 알린다 */
    public void clearAll(String reason) {
        synchronized (this) {
            seenVersion = DataChanges.version();
            seenHomeVersion = DataChanges.homeVersion();
        }
        clear(ALL, reason);
    }

    /**
     * 홈 캐시만 지우고 알린다(포스팅 상태처럼 홈에만 나오는 시각 값이 바뀌었을 때). 기사 표시(homeVersion)는 그대로 둔다:
     * 기사는 선수 페이지에도 나오므로, 그 사이 쓴 기사는 clearIfDataChanged가 홈·선수 캐시를 함께 지운다.
     */
    public void clearHome(String reason) {
        clear(HOME, reason);
    }

    /** 기사가 나오는 캐시(홈·선수 페이지)만 지우고 알린다 */
    private void clearNews(String reason) {
        synchronized (this) {
            seenHomeVersion = DataChanges.homeVersion();
        }
        clear(NEWS, reason);
    }

    /**
     * 알리지 않고 지운다. 지운 순간에 계산 중이던 요청이 옛 데이터로 만든 응답을 그 뒤에 넣을 수 있어서,
     * 다시 채우기 전에 한 번 더 지울 때 쓴다(CacheWarmer).
     */
    public void clearQuietly(List<String> names) {
        for (String name : names) {
            Cache cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        }
    }

    private void clear(List<String> names, String reason) {
        clearQuietly(names);
        log.info("response caches cleared {}: {}", names, reason);
        events.publishEvent(new ResponseCachesCleared(reason, names));
    }
}
