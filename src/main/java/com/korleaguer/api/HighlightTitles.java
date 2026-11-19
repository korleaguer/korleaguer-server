package com.korleaguer.api;

import com.korleaguer.config.DataMode;
import com.korleaguer.domain.HighlightTitle;
import com.korleaguer.domain.HighlightTitleRepository;
import com.korleaguer.news.HighlightTitleTranslator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.regex.Pattern;

/**
 * 하이라이트 영어 제목의 한국어 번역(이슈 #199). 경기 상세 응답을 기다리게 하지 않으려고 저장된 번역만 바로 붙이고,
 * 없는 제목은 뒤에서 옮겨 저장한다(다음 요청부터 보인다). 이번 응답의 titleKo는 null이다: 제목 하나에 AI 응답이 1~2초
 * 걸리고 한 경기에 최대 6개라, 처음 열 때 창이 그만큼 늦게 뜨는 것보다 영어 제목이 한 번 더 보이는 쪽이 낫다.
 * <ul>
 *   <li>같은 영어 제목은 DB(highlight_title)에 한 줄로 두고 모든 선수·경기가 다시 쓴다.</li>
 *   <li>같은 제목을 이미 옮기는 중이면 또 옮기지 않는다(inFlight).</li>
 *   <li>못 옮기면(빈 답·못 쓸 답) 그 제목은 RETRY_AFTER 동안 다시 부르지 않는다. AI 서비스가 답하지 못하면(키·결제·사용량·장애)
 *       모든 제목을 PAUSE 동안 쉰다. 키가 거절되면(401·403) 번역기가 서버를 다시 띄울 때까지 스스로 끈다.</li>
 *   <li>키가 없거나(번역기 OFF) 샘플 모드면 AI를 부르지 않고 저장된 번역만 쓴다.</li>
 * </ul>
 * 경기 상세 JSON(player_game_detail)에는 한국어 제목을 넣지 않고 응답을 줄 때마다 붙이므로, 번역이 늦게 생겨도 굳은
 * 상세에 바로 반영된다.
 */
@Service
public class HighlightTitles {

    private static final Logger log = LoggerFactory.getLogger(HighlightTitles.class);

    /** 못 옮긴 제목을 다시 해 보기까지 */
    static final Duration RETRY_AFTER = Duration.ofHours(1);
    /** AI 서비스가 답하지 못했을 때 모든 제목을 쉬는 시간 */
    static final Duration PAUSE = Duration.ofMinutes(5);
    /** title_en 칸 길이(V27) */
    static final int MAX_TITLE = 300;
    private static final int MAX_REMEMBERED_FAILURES = 500;
    private static final Pattern HANGUL = Pattern.compile("[가-힣]");

    private final HighlightTitleRepository titles;
    private final HighlightTitleTranslator translator;
    private final DataMode dataMode;
    private final Clock clock;
    private final ExecutorService executor;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final Map<String, Instant> failedUntil = new ConcurrentHashMap<>();
    private volatile Instant pausedUntil = Instant.MIN;

    public HighlightTitles(HighlightTitleRepository titles, HighlightTitleTranslator translator, DataMode dataMode,
                           Clock clock, @Qualifier("ingestExecutor") ExecutorService executor) {
        this.titles = titles;
        this.translator = translator;
        this.dataMode = dataMode;
        this.clock = clock;
        this.executor = executor;
    }

    /**
     * 저장된 한국어 제목(영어 제목 → 한국어). 없는 제목은 빠지고, 뒤에서 옮기기 시작한다. 선수는 영상이 뜨는 창의 선수다
     * (제목에서 그 선수 이름을 빼고 옮긴다)
     */
    public Map<String, String> koreanOf(Collection<String> titlesEn, String playerKo, String playerEn) {
        List<String> wanted = titlesEn.stream()
                .filter(t -> t != null && !t.isBlank() && t.length() <= MAX_TITLE && !HANGUL.matcher(t).find())
                .distinct().toList();
        if (wanted.isEmpty()) {
            return Map.of();
        }
        Map<String, String> known = new HashMap<>();
        Set<String> upToDate = ConcurrentHashMap.newKeySet();
        for (HighlightTitle row : titles.findByTitleEnIn(wanted)) {
            known.put(row.getTitleEn(), row.getTitleKo());
            if (row.getPromptVersion().equals(translator.version())) {
                upToDate.add(row.getTitleEn());
            }
        }
        if (translator.enabled() && dataMode != DataMode.SAMPLE) {
            wanted.stream().filter(t -> !upToDate.contains(t)).forEach(t -> translateInBackground(t, playerKo, playerEn));
        }
        return known;
    }

    private void translateInBackground(String title, String playerKo, String playerEn) {
        Instant now = clock.instant();
        Instant failed = failedUntil.get(title);
        if (now.isBefore(pausedUntil) || (failed != null && now.isBefore(failed)) || !inFlight.add(title)) {
            return;
        }
        try {
            executor.execute(() -> translateAndSave(title, playerKo, playerEn));
        } catch (RuntimeException e) {
            // 앱이 내려가는 중 등: 다음 요청에서 다시 한다
            inFlight.remove(title);
        }
    }

    /** 뒤 스레드에서 한 제목을 옮겨 저장한다(테스트는 직접 부른다) */
    void translateAndSave(String title, String playerKo, String playerEn) {
        try {
            Optional<String> korean = translator.translate(title, playerKo, playerEn);
            if (korean.isEmpty()) {
                remember(title);
                return;
            }
            Instant now = clock.instant();
            HighlightTitle row = titles.findByTitleEn(title).orElseGet(() -> new HighlightTitle(title));
            row.translated(korean.get(), translator.version(), now);
            try {
                titles.save(row);
            } catch (DataIntegrityViolationException e) {
                log.debug("highlight title '{}' saved by another request", title);
            }
        } catch (RuntimeException e) {
            // Summarizer.Failure(AI 서비스가 답하지 못함)와 저장 실패. 잠시 모든 제목을 쉰다
            pausedUntil = clock.instant().plus(PAUSE);
            log.warn("highlight title translation failed, pausing {}: {}", PAUSE, e.getMessage());
        } finally {
            inFlight.remove(title);
        }
    }

    private void remember(String title) {
        Instant now = clock.instant();
        if (failedUntil.size() >= MAX_REMEMBERED_FAILURES) {
            failedUntil.values().removeIf(until -> !now.isBefore(until));
            if (failedUntil.size() >= MAX_REMEMBERED_FAILURES) {
                failedUntil.clear();
            }
        }
        failedUntil.put(title, now.plus(RETRY_AFTER));
        log.warn("highlight title could not be translated, not trying for {}: {}", RETRY_AFTER, title);
    }
}
