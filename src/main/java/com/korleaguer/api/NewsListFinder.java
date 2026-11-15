package com.korleaguer.api;

import com.korleaguer.domain.NewsArticle;
import com.korleaguer.domain.NewsArticleRepository;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerRepository;
import com.korleaguer.ingest.RosterProperties;
import com.korleaguer.news.MlbContext;
import com.korleaguer.news.NewsProperties;
import com.korleaguer.photo.PhotoSources;
import com.korleaguer.posting.PostingCase;
import com.korleaguer.posting.Postings;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 뉴스 탭 한 쪽(NewsListResponse)을 만든다(이슈 #153). DB의 기사와 명단·posting.yml만 읽는다.
 * <ul>
 *   <li>선수: 이번 겨울 포스팅 선수와 명단 선수(국내 리그로 돌아간 선수는 빼고, 기사를 모으는 선수와 같다). 같은 이름이면
 *       명단 선수다(포스팅 뒤 계약해 명단에 들어온 선수).</li>
 *   <li>기사 규칙은 홈·선수 화면과 같다: 지금 수집하는 언론사만, 선수가 주인공이 아닌 기사는 빼고(AI 판단, 이슈 #129),
 *       포스팅 선수의 국내 기사는 제목이 MLB 이야기인 것만(MlbContext, 이슈 #127), 언론사만 다른 같은 기사는 한 줄(SameArticles).
 *       전체 보기에서 여러 선수에 걸린 같은 기사도 SameArticles가 한 줄로 묶는다(제목이 같다).</li>
 *   <li>쪽 넘기기: 줄은 묶음의 가장 먼저 낸 기사 순(늦은 순)이고, 커서는 그 쪽 마지막 줄의 가장 먼저 낸 기사다. 그래서
 *       보인 묶음의 기사는 모두 커서보다 늦어 다음 쪽에 다시 나오지 않고, 안 보인 묶음은 가장 먼저 낸 기사가 커서보다 일러
 *       다음 쪽에 나온다. 마지막 줄의 묶음이 덜 모이지 않게, 그 줄보다 SameArticles.NEAR만큼 더 오래된 기사까지 읽고 묶는다.</li>
 * </ul>
 */
@Component
@Transactional(readOnly = true)
public class NewsListFinder {

    /** 한 쪽 줄 수 */
    static final int PAGE = 20;
    /** 한 번에 읽는 기사 수. 걸러지거나 묶여 줄이 모자라면 더 읽는다 */
    static final int BATCH = 100;
    /** 한 쪽을 만들려고 읽는 기사 수의 상한(그래도 모자라면 거기까지 보이고 다음 쪽 커서를 준다) */
    static final int MAX_ROWS = 600;

    private final NewsArticleRepository news;
    private final Postings postings;
    private final RosterProperties roster;
    private final PlayerRepository players;
    private final PhotoSources photos;
    /** 지금 news/sources.yml에 있는 언론사 */
    private final Set<String> publishers;
    /** 영어 MLB 매체. 포스팅 선수의 국내 기사만 제목이 MLB 이야기인지 본다 */
    private final Set<String> aiPublishers;

    public NewsListFinder(NewsArticleRepository news, Postings postings, RosterProperties roster,
                          PlayerRepository players, PhotoSources photos, NewsProperties newsProperties) {
        this.news = news;
        this.postings = postings;
        this.roster = roster;
        this.players = players;
        this.photos = photos;
        this.publishers = newsProperties.publishers();
        this.aiPublishers = newsProperties.aiPublishers();
    }

    /**
     * @param player 거를 선수(한글 이름). null이나 빈 값이면 전체
     * @param before 앞 쪽이 준 커서(next). null이나 빈 값이면 첫 쪽
     * @throws BadRequestException 커서 모양이 틀렸거나 선수 이름이 너무 길 때
     */
    public NewsListResponse find(String player, String before) {
        Cursor cursor = Cursor.parse(before);
        String wanted = player == null || player.isBlank() ? null : player.trim();
        if (wanted != null && wanted.length() > 50) {
            throw new BadRequestException("player is too long");
        }
        Map<String, NewsListResponse.Subject> all = subjects();
        if (all.isEmpty() || publishers.isEmpty()) {
            return new NewsListResponse(List.of(), null, List.of(), null);
        }
        Set<String> withArticles = new HashSet<>(news.findSubjectsWithArticles(all.keySet(), publishers));
        List<NewsListResponse.Subject> chips = all.values().stream()
                .filter(s -> withArticles.contains(s.nameKo()))
                .toList();
        if (wanted != null && chips.stream().noneMatch(s -> s.nameKo().equals(wanted))) {
            return new NewsListResponse(chips, null, List.of(), null);
        }
        List<String> scope = wanted != null ? List.of(wanted) : chips.stream().map(NewsListResponse.Subject::nameKo).toList();
        if (scope.isEmpty()) {
            return new NewsListResponse(chips, null, List.of(), null);
        }
        Set<String> postingOnly = new HashSet<>();
        chips.stream().filter(NewsListResponse.Subject::posting).forEach(s -> postingOnly.add(s.nameKo()));
        Page page = page(scope, postingOnly, cursor);
        return new NewsListResponse(chips, wanted, page.items(), page.next());
    }

    private record Page(List<NewsListResponse.Item> items, String next) {
    }

    private Page page(List<String> scope, Set<String> postingOnly, Cursor cursor) {
        List<NewsArticle> kept = new ArrayList<>();
        List<SameArticles.Group> groups = List.of();
        Cursor read = cursor;
        int rows = 0;
        boolean exhausted = false;
        while (true) {
            List<NewsArticle> batch = news.findPage(scope, publishers, read.at(), read.id(), PageRequest.of(0, BATCH));
            rows += batch.size();
            exhausted = batch.size() < BATCH;
            if (!batch.isEmpty()) {
                read = Cursor.of(batch.getLast());
            }
            batch.stream().filter(a -> kept(a, postingOnly)).forEach(kept::add);
            groups = byEarliest(SameArticles.group(kept));
            if (exhausted || rows >= MAX_ROWS) {
                break;
            }
            // 줄이 한 쪽보다 많고, 이 쪽 마지막 줄의 같은 기사(NEAR 안)까지 다 읽었으면 그만 읽는다
            if (groups.size() > PAGE
                    && !read.at().isAfter(earliest(groups.get(PAGE - 1)).getPublishedAt().minus(SameArticles.NEAR))) {
                break;
            }
        }
        List<SameArticles.Group> shown = groups.subList(0, Math.min(PAGE, groups.size()));
        String next = null;
        if (groups.size() > PAGE || (!exhausted && !shown.isEmpty())) {
            next = Cursor.of(earliest(shown.getLast())).toString();
        } else if (!exhausted) {
            // 읽은 기사가 모두 걸러졌다(상한까지 읽고도 한 줄도 없다). 읽은 데까지 넘겨 다음 쪽에서 이어 읽는다
            next = read.toString();
        }
        return new Page(shown.stream().map(NewsListFinder::item).toList(), next);
    }

    /** 포스팅 선수(명단 밖)의 국내 기사는 제목이 MLB 이야기일 때만(홈 MLB 도전 카드와 같은 기준) */
    private boolean kept(NewsArticle a, Set<String> postingOnly) {
        return !postingOnly.contains(a.getSubject())
                || aiPublishers.contains(a.getPublisher())
                || MlbContext.mentions(a.getTitle());
    }

    /** 묶음을 가장 먼저 낸 기사의 늦은 순으로(같은 시각이면 번호가 큰 쪽이 앞, 커서와 같은 순서) */
    private static List<SameArticles.Group> byEarliest(List<SameArticles.Group> groups) {
        Comparator<NewsArticle> newestFirst = Comparator.comparing(NewsArticle::getPublishedAt)
                .thenComparing(NewsArticle::getId)
                .reversed();
        return groups.stream().sorted(Comparator.comparing(NewsListFinder::earliest, newestFirst)).toList();
    }

    /** 묶음에서 가장 먼저 낸 기사(members는 낸 순) */
    private static NewsArticle earliest(SameArticles.Group group) {
        return group.members().getFirst();
    }

    private static NewsListResponse.Item item(SameArticles.Group g) {
        NewsArticle a = g.article();
        List<String> who = Stream.concat(Stream.of(a), g.members().stream())
                .map(NewsArticle::getSubject)
                .distinct()
                .toList();
        return new NewsListResponse.Item(a.getId(), a.getTitle(), a.getUrl(), a.getPublisher(), a.getPublishedAt(),
                a.getTitleKo(), a.getImageUrl(), g.alsoBy(), who);
    }

    /** 고를 수 있는 선수: 포스팅 선수(파일 순서) 먼저, 그다음 명단 순. 같은 이름이면 명단 선수 값으로(자리는 그대로) */
    private Map<String, NewsListResponse.Subject> subjects() {
        Map<String, NewsListResponse.Subject> out = new LinkedHashMap<>();
        for (PostingCase posting : postings.current()) {
            String name = posting.nameKo().trim();
            out.put(name, new NewsListResponse.Subject(name, photos.postingPath(posting.slug()), null, true));
        }
        List<RosterProperties.Entry> entries = roster.baseball() == null ? List.of() : roster.baseball();
        for (RosterProperties.Entry entry : entries) {
            // 국내 리그로 돌아간 선수는 해외파 소식이 아니다(기사도 모으지 않는다, NewsCollector)
            if (entry.nameKo() == null || entry.returnedKo() != null) {
                continue;
            }
            String name = entry.nameKo().trim();
            Optional<Player> player = entry.mlbId() != null ? players.findByMlbId(entry.mlbId()) : players.findByNameKo(name);
            out.put(name, new NewsListResponse.Subject(name, photos.mlbPath(entry.mlbId()),
                    player.map(Player::getId).orElse(null), false));
        }
        return out;
    }

    /** 쪽 넘기기 자리: 이 기사보다 오래된 기사부터. 문자열은 "발행 시각(ISO-8601)_번호" */
    record Cursor(Instant at, long id) {

        /** 첫 쪽: 모든 기사보다 늦은 자리 */
        static final Cursor TOP = new Cursor(Instant.parse("9999-12-31T00:00:00Z"), Long.MAX_VALUE);

        static Cursor of(NewsArticle a) {
            return new Cursor(a.getPublishedAt(), a.getId());
        }

        static Cursor parse(String value) {
            if (value == null || value.isBlank()) {
                return TOP;
            }
            int split = value.lastIndexOf('_');
            if (split <= 0 || value.length() > 64) {
                throw new BadRequestException("before must be a cursor from next");
            }
            try {
                return new Cursor(Instant.parse(value.substring(0, split)), Long.parseLong(value.substring(split + 1)));
            } catch (DateTimeParseException | NumberFormatException e) {
                throw new BadRequestException("before must be a cursor from next");
            }
        }

        @Override
        public String toString() {
            return at + "_" + id;
        }
    }
}
