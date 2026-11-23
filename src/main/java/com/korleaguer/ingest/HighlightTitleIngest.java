package com.korleaguer.ingest;

import com.korleaguer.config.DataMode;
import com.korleaguer.domain.GameDetail;
import com.korleaguer.domain.HighlightTitle;
import com.korleaguer.domain.HighlightTitleRepository;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerGameDetail;
import com.korleaguer.domain.PlayerGameDetailRepository;
import com.korleaguer.domain.PlayerRepository;
import com.korleaguer.news.HighlightTitleTranslator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 경기 상세 하이라이트의 영어 제목을 한국어로 미리 옮겨 highlight_title에 둔다(이슈 #199·#237).
 * 화면용 API(api.HighlightTitles)는 여기서 저장한 번역만 읽는다. 전에는 누가 경기 상세를 열어야 그 제목을 옮겼다.
 * <ul>
 *   <li>옮길 제목을 찾는 길은 둘이다. (1) 경기 상세를 저장할 때 그 상세의 제목을 바로 대기 줄 맨 앞에 넣는다(offer, GameDetailIngest가 부른다).
 *       (2) 이미 저장된 상세(player_game_detail.payload의 JSON)는 서버가 뜬 뒤 한 번만 훑는다: 큰 번호부터 SCAN_PAGE줄씩,
 *       대기 줄에 이번 실행 몫이 찰 때까지만, 한 번 실행에 많아야 SCAN_PAGES_PER_RUN묶음. 끝까지 훑은 뒤에는 저장된 JSON을 다시 읽지 않는다.</li>
 *   <li>대기 줄과 어디까지 훑었는지는 메모리에 둔다(수집은 한 번에 하나만 돈다). 서버를 다시 띄우면 처음부터 다시 훑으므로 밀린 제목이 빠지지 않는다.
 *       이미 옮긴 제목은 번역 표에 있어 다시 옮기지 않는다.</li>
 *   <li>옮기지 않는 제목: 비어 있음, title_en 칸(MAX_TITLE)보다 김, 이미 한글이 들어 있음.</li>
 *   <li>같은 영어 제목은 한 줄이고 모든 선수·경기가 같이 쓴다. 번역기에는 그 제목이 나온 경기 상세의 선수 이름을 넘긴다(제목에서 그 선수 이름을 뺀다).</li>
 *   <li>번역 규칙 판(translator.version())이 다른 옛 번역은 다시 옮겨 덮어쓴다.</li>
 *   <li>한 번 실행에 AI를 limit번까지만 부른다(못 옮긴 호출도 센다). 수집 잠금을 오래 잡지 않게 한다.</li>
 *   <li>못 옮긴 제목(빈 답·못 쓸 답)은 RETRY_AFTER 동안 다시 부르지 않고, 또 못 옮길 때마다 쉬는 시간을 두 배로 늘린다(MAX_RETRY_AFTER까지).
 *       영영 못 옮기는 제목을 한 시간마다 끝없이 부르지 않게 한다(서버를 다시 띄우면 처음부터 센다). AI 서비스가 답하지 못하거나 저장에 실패하면 그 실행을 멈추고
 *       모든 제목을 PAUSE 동안 쉰다. 키가 거절되면(401·403) 번역기가 서버를 다시 띄울 때까지 스스로 끈다.</li>
 *   <li>번역기가 꺼져 있거나(키 없음) 샘플 모드면 AI도 부르지 않고 훑지도 않는다.</li>
 * </ul>
 * 번역은 부가 기능이라 여기서 난 오류는 밖으로 던지지 않는다(수집의 다른 단계를 멈추지 않는다).
 */
@Component
public class HighlightTitleIngest {

    private static final Logger log = LoggerFactory.getLogger(HighlightTitleIngest.class);

    /** 못 옮긴 제목을 다시 해 보기까지 */
    static final Duration RETRY_AFTER = Duration.ofHours(1);
    /** 못 옮긴 제목이 쉬는 시간의 끝(여러 번 못 옮기면 하루에 한 번만 다시 해 본다) */
    static final Duration MAX_RETRY_AFTER = Duration.ofDays(1);
    /** AI 서비스가 답하지 못했을 때 모든 제목을 쉬는 시간 */
    static final Duration PAUSE = Duration.ofMinutes(5);
    /** title_en 칸 길이(V27) */
    static final int MAX_TITLE = 300;
    /** 저장된 상세를 훑을 때 한 번에 읽는 줄 수 */
    static final int SCAN_PAGE = 50;
    /** 한 번 실행에 훑는 묶음 수(많아야 SCAN_PAGE × 이 값 = 200줄) */
    static final int SCAN_PAGES_PER_RUN = 4;
    private static final Pattern HANGUL = Pattern.compile("[가-힣]");

    private final HighlightTitleRepository titles;
    private final PlayerGameDetailRepository details;
    private final PlayerRepository players;
    private final HighlightTitleTranslator translator;
    private final DataMode dataMode;
    private final JsonMapper json;
    private final Clock clock;

    /** 옮길 제목(영어 제목 → 넘길 선수 이름과 다시 해 볼 시각). 앞에서부터 옮긴다 */
    private final LinkedHashMap<String, Waiting> waiting = new LinkedHashMap<>();
    /** 저장된 상세를 어디까지 훑었나: 이 번호보다 작은 줄이 남았다 */
    private long scanBefore = Long.MAX_VALUE;
    private boolean scanned;
    private Instant pausedUntil = Instant.MIN;
    private final AtomicLong translated = new AtomicLong();

    public HighlightTitleIngest(HighlightTitleRepository titles, PlayerGameDetailRepository details,
                                PlayerRepository players, HighlightTitleTranslator translator, DataMode dataMode,
                                JsonMapper json, Clock clock) {
        this.titles = titles;
        this.details = details;
        this.players = players;
        this.translator = translator;
        this.dataMode = dataMode;
        this.json = json;
        this.clock = clock;
    }

    /**
     * 밀린 제목을 AI 호출 limit번까지 옮겨 저장한다.
     *
     * @return 수집 기록에 남길 말. 옮긴 것이 없으면 빈 문자열
     */
    public synchronized String translate(int limit) {
        if (off() || clock.instant().isBefore(pausedUntil)) {
            return "";
        }
        int done = 0;
        try {
            scan(limit);
            for (String title : ready(limit)) {
                Waiting target = waiting.get(title);
                Optional<String> korean = translator.translate(title, target.playerKo(), target.playerEn());
                if (korean.isEmpty()) {
                    Waiting resting = target.failedAt(clock.instant());
                    waiting.put(title, resting);
                    log.warn("highlight title could not be translated ({} times), not trying until {}: {}",
                            resting.failures(), resting.notBefore(), title);
                    continue;
                }
                save(title, korean.get());
                waiting.remove(title);
                done++;
            }
        } catch (RuntimeException e) {
            // Summarizer.Failure(AI 서비스가 답하지 못함)와 DB 오류. 이번 실행은 여기서 멈추고 잠시 모든 제목을 쉰다
            pausedUntil = clock.instant().plus(PAUSE);
            log.warn("highlight title translation failed, pausing {}: {}", PAUSE, e.getMessage());
        }
        translated.addAndGet(done);
        return done == 0 ? "" : "하이라이트 제목 번역 " + done + "개";
    }

    /**
     * 방금 저장한 경기 상세의 하이라이트 제목을 대기 줄 맨 앞에 넣는다(새 경기의 제목을 밀린 옛 제목보다 먼저 옮긴다).
     * AI는 부르지 않는다. 다음 translate가 옮긴다.
     */
    public synchronized void offer(GameDetail detail, Player player) {
        if (off() || detail.highlights().isEmpty()) {
            return;
        }
        try {
            Map<String, Player> found = new LinkedHashMap<>();
            detail.highlights().forEach(h -> found.putIfAbsent(h.title(), player));
            enqueue(found, true);
        } catch (RuntimeException e) {
            // 경기 상세 수집을 멈추지 않는다. 이 제목을 놓치지 않게 저장된 상세를 처음부터 다시 훑는다
            log.warn("highlight titles of game {} could not be queued, scanning saved details again: {}",
                    detail.gamePk(), e.getMessage());
            scanBefore = Long.MAX_VALUE;
            scanned = false;
        }
    }

    /** 지금까지 옮겨 저장한 제목 수(서버가 뜬 뒤). 수집 기록을 남길지 정할 때 실행 앞뒤로 견준다 */
    public long translatedCount() {
        return translated.get();
    }

    /** 대기 줄에 있는 제목 수(쉬는 제목 포함) */
    synchronized int waitingCount() {
        return waiting.size();
    }

    /** 저장된 상세를 끝까지 훑었나 */
    synchronized boolean scanned() {
        return scanned;
    }

    private boolean off() {
        return !translator.enabled() || dataMode == DataMode.SAMPLE;
    }

    // 저장된 상세를 큰 번호부터 훑어 옮길 제목을 대기 줄 뒤에 넣는다. 이번 실행 몫(limit)이 차면 멈추고 다음 실행이 이어서 훑는다
    private void scan(int limit) {
        for (int page = 0; !scanned && page < SCAN_PAGES_PER_RUN && ready(limit).size() < limit; page++) {
            List<PlayerGameDetail> rows = details.findByIdLessThanOrderByIdDesc(scanBefore, Limit.of(SCAN_PAGE));
            enqueue(titlesOf(rows), false);
            // 대기 줄에 넣은 뒤에 자리를 옮긴다(넣다 실패하면 다음 실행이 이 묶음부터 다시 훑는다)
            if (!rows.isEmpty()) {
                scanBefore = rows.getLast().getId();
            }
            scanned = rows.size() < SCAN_PAGE;
        }
    }

    // 저장된 상세 묶음에 나온 하이라이트 제목과 그 제목이 나온 상세의 선수(같은 제목은 먼저 나온 상세의 선수)
    private Map<String, Player> titlesOf(List<PlayerGameDetail> rows) {
        List<GameDetail> withHighlights = new ArrayList<>();
        for (PlayerGameDetail row : rows) {
            read(row).filter(d -> !d.highlights().isEmpty()).ifPresent(withHighlights::add);
        }
        Map<String, Player> found = new LinkedHashMap<>();
        if (withHighlights.isEmpty()) {
            return found;
        }
        Map<Long, Player> byId = new HashMap<>();
        players.findAllById(withHighlights.stream().map(GameDetail::playerId).collect(Collectors.toSet()))
                .forEach(p -> byId.put(p.getId(), p));
        for (GameDetail detail : withHighlights) {
            detail.highlights().forEach(h -> found.putIfAbsent(h.title(), byId.get(detail.playerId())));
        }
        return found;
    }

    // 옮길 수 있는 제목 가운데 지금 판 번역이 없는 것만 대기 줄에 넣는다(이미 줄에 있으면 그대로 둔다)
    private void enqueue(Map<String, Player> found, boolean first) {
        List<String> wanted = found.entrySet().stream()
                .filter(e -> translatable(e.getKey()) && named(e.getValue()) && !waiting.containsKey(e.getKey()))
                .map(Map.Entry::getKey)
                .toList();
        if (wanted.isEmpty()) {
            return;
        }
        String version = translator.version();
        Set<String> upToDate = titles.findByTitleEnIn(wanted).stream()
                .filter(row -> row.getPromptVersion().equals(version))
                .map(HighlightTitle::getTitleEn)
                .collect(Collectors.toSet());
        // 맨 앞에 넣을 때는 뒤 제목부터 넣어 올라온 순서를 지킨다
        for (String title : first ? wanted.reversed() : wanted) {
            if (upToDate.contains(title)) {
                continue;
            }
            Player player = found.get(title);
            Waiting target = new Waiting(player.getNameKo(), player.getNameEn(), Instant.MIN, 0);
            if (first) {
                waiting.putFirst(title, target);
            } else {
                waiting.put(title, target);
            }
        }
    }

    static boolean translatable(String title) {
        return title != null && !title.isBlank() && title.length() <= MAX_TITLE && !HANGUL.matcher(title).find();
    }

    // 번역기에 넘길 영어 이름이 있는 선수만(아직 선수 정보를 못 받았으면 넘길 이름이 없다)
    private static boolean named(Player player) {
        return player != null && player.getNameEn() != null;
    }

    // 지금 옮겨도 되는 제목을 대기 줄 앞에서부터 limit개까지
    private List<String> ready(int limit) {
        Instant now = clock.instant();
        return waiting.entrySet().stream()
                .filter(e -> !now.isBefore(e.getValue().notBefore()))
                .map(Map.Entry::getKey)
                .limit(limit)
                .toList();
    }

    private void save(String title, String korean) {
        HighlightTitle row = titles.findByTitleEn(title).orElseGet(() -> new HighlightTitle(title));
        row.translated(korean, translator.version(), clock.instant());
        try {
            titles.save(row);
        } catch (DataIntegrityViolationException e) {
            // 배포 중 겹쳐 뜬 옛 서버가 같은 제목을 먼저 저장했다
            log.debug("highlight title '{}' saved by another server", title);
        }
    }

    private Optional<GameDetail> read(PlayerGameDetail row) {
        try {
            return Optional.of(json.readValue(row.getPayload(), GameDetail.class));
        } catch (JacksonException e) {
            log.warn("game detail {} for player {}: stored JSON is unreadable, its highlight titles are skipped",
                    row.getGamePk(), row.getPlayerId());
            return Optional.empty();
        }
    }

    /** 옮길 제목 하나: 번역기에 넘길 선수 이름, 이 시각 전에는 부르지 않는다(못 옮긴 뒤 쉬는 중), 못 옮긴 횟수 */
    private record Waiting(String playerKo, String playerEn, Instant notBefore, int failures) {

        /** 또 못 옮겼다: RETRY_AFTER × 2^(횟수-1)만큼, 많아야 MAX_RETRY_AFTER 쉰다 */
        Waiting failedAt(Instant now) {
            int count = failures + 1;
            Duration rest = RETRY_AFTER.multipliedBy(1L << Math.min(count - 1, 10));
            if (rest.compareTo(MAX_RETRY_AFTER) > 0) {
                rest = MAX_RETRY_AFTER;
            }
            return new Waiting(playerKo, playerEn, now.plus(rest), count);
        }
    }
}
