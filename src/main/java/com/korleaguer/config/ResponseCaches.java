package com.korleaguer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 화면용 응답 캐시(홈·선수·내 선수·내 선수 카드)를 지우는 곳. 데이터가 실제로 바뀌었을 때만, 바뀐 것이 나오는 캐시만 지운다.
 * <ul>
 *   <li>clearIfDataChanged: 지난번 확인 뒤 DB에 쓴 것(DataChanges)이 있으면 지운다. 모든 응답에 나오는 데이터면 모두,
 *       홈에만 나오는 데이터(기사 제목)뿐이면 홈만. 수집이 끝날 때(IngestService)와 12분마다(CacheWarmer, 기사처럼 수집 밖에서 쓴 것) 부른다.</li>
 *   <li>clearAll·clearHome: 그냥 지운다. 시각으로 바뀌는 값(날짜는 모두, 포스팅 상태는 홈만)이 바뀌었을 때 부른다.</li>
 * </ul>
 * 지우면 ResponseCachesCleared를 보낸다(api.CacheWarmer가 다시 채운다).
 */
@Component
public class ResponseCaches {

    private static final Logger log = LoggerFactory.getLogger(ResponseCaches.class);
    public static final List<String> ALL = List.of(AppConfig.HOME_CACHE, AppConfig.PLAYER_CACHE, AppConfig.MY_CACHE,
            AppConfig.CARD_CACHE);
    public static final List<String> HOME = List.of(AppConfig.HOME_CACHE);

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
            clearHome(reason + "(홈에만 나오는 데이터)");
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

    /** 홈 캐시만 지우고 알린다(기사 제목·포스팅 상태처럼 홈에만 나오는 값이 바뀌었을 때) */
    public void clearHome(String reason) {
        synchronized (this) {
            seenHomeVersion = DataChanges.homeVersion();
        }
        clear(HOME, reason);
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
