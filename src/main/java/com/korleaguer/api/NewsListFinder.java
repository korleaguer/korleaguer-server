package com.korleaguer.api;

import com.korleaguer.domain.Affiliation;
import com.korleaguer.domain.AffiliationRepository;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 뉴스 탭 한 쪽(NewsListResponse)을 만든다(이슈 #153). DB의 기사와 명단·posting.yml만 읽는다.
 * <ul>
 *   <li>선수: 이번 겨울 포스팅 선수와 명단 선수(국내 리그로 돌아간 선수는 빼고, 기사를 모으는 NewsCollector.subjects와 같은
 *       규칙). 같은 이름이면 포스팅 선수로 본다(수집·기사 화면과 같다). 명단에도 있으면 선수 번호만 명단에서 가져온다.
 *       포스팅 선수는 MLB 이야기 기사가 하나라도 있어야 고르기 목록에 나온다.</li>
 *   <li>기사 규칙은 홈·선수 화면과 같다: 지금 수집하는 언론사만, 선수가 주인공이 아닌 기사는 빼고(AI 판단, 이슈 #129),
 *       포스팅 선수의 국내 기사는 제목이 MLB 이야기인 것만(MlbContext, 이슈 #127), 언론사만 다른 같은 기사는 한 줄(SameArticles).
 *       전체 보기에서 여러 선수에 걸린 같은 기사도 SameArticles가 한 줄로 묶는다(제목이 같다).</li>
 *   <li>쪽 넘기기: 줄은 묶음의 가장 먼저 낸 기사 순(늦은 순)이고, 커서(C)는 그 쪽 마지막 줄의 가장 먼저 낸 기사다.
 *       가장 먼저 낸 기사가 C 이후인 묶음은 이미 보였고, C보다 이른 묶음은 아직 안 보였다. 다음 쪽은 C보다 NEAR만큼 늦은
 *       데서부터 다시 읽고 묶은 뒤 안 보인 묶음만 남긴다. 그래야 안 보인 묶음의 사본 중 C보다 늦게 나온 것(요약이 붙은 대표,
 *       두 사본을 이어 주던 기사)도 함께 묶여 한 줄이 된다. 같은 데이터면 묶음은 늘 같아(읽은 범위가 줄면 묶음이 나뉠 뿐
 *       합쳐지지 않는다) 보인 묶음의 조각은 다시 나오지 않는다.</li>
 *   <li>쪽 끝: 마지막 줄의 묶음이 덜 모이지 않게, 그 줄보다 NEAR만큼 더 오래된 기사까지 읽고 묶는다. 읽는 상한(MAX_ROWS)에
 *       걸리면 다 모인 묶음(가장 먼저 낸 기사가 읽은 끝보다 NEAR 넘게 늦은 것)까지만 보인다.</li>
 * </ul>
 */
@Component
@Transactional(readOnly = true)
public class NewsListFinder {

    /** 한 쪽 줄 수 */
    static final int PAGE = 20;
    /** 한 번에 읽는 기사 수. 걸러지거나 묶여 줄이 모자라면 더 읽는다 */
    static final int BATCH = 200;
    /** 한 쪽을 만들려고 읽는 기사 수의 상한(앞 쪽과 겹쳐 다시 읽는 NEAR 범위 포함). 걸리면 다 모인 묶음까지만 보인다 */
    static final int MAX_ROWS = 2000;
    /** 검색어 길이 상한 */
    static final int MAX_QUERY = 30;
    /** 많이 나온 말을 셀 최근 기사 수(운영 규모에서 2~3주치) */
    static final int TOPIC_ROWS = 300;

    private final NewsArticleRepository news;
    private final Postings postings;
    private final RosterProperties roster;
    private final PlayerRepository players;
    private final AffiliationRepository affiliations;
    private final PhotoSources photos;
    /** 지금 news/sources.yml에 있는 언론사 */
    private final Set<String> publishers;
    /** 영어 MLB 매체. 포스팅 선수의 국내 기사만 제목이 MLB 이야기인지 본다 */
    private final Set<String> aiPublishers;

    public NewsListFinder(NewsArticleRepository news, Postings postings, RosterProperties roster,
                          PlayerRepository players, AffiliationRepository affiliations, PhotoSources photos,
                          NewsProperties newsProperties) {
        this.news = news;
        this.postings = postings;
        this.roster = roster;
        this.players = players;
        this.affiliations = affiliations;
        this.photos = photos;
        this.publishers = newsProperties.publishers();
        this.aiPublishers = newsProperties.aiPublishers();
    }

    /**
     * @param player 거를 선수(한글 이름). null이나 빈 값이면 전체
     * @param before 앞 쪽이 준 커서(next). null이나 빈 값이면 첫 쪽
     * @param query  검색어(제목에 있는 기사만, 이슈 #158·#161). null이나 빈 값이면 거르지 않는다
     * @throws BadRequestException 커서 모양이 틀렸거나 선수 이름·검색어가 너무 길 때
     */
    public NewsListResponse find(String player, String before, String query) {
        Cursor cursor = Cursor.parse(before);
        String wanted = player == null || player.isBlank() ? null : player.trim();
        if (wanted != null && wanted.length() > 50) {
            throw new BadRequestException("player is too long");
        }
        String q = query == null || query.isBlank() ? null : query.trim();
        if (q != null && q.length() > MAX_QUERY) {
            throw new BadRequestException("q is too long");
        }
        Map<String, NewsListResponse.Subject> all = subjects();
        if (all.isEmpty() || publishers.isEmpty()) {
            return new NewsListResponse(List.of(), null, List.of(), null);
        }
        Map<String, Instant> latest = new HashMap<>();
        news.findLatestBySubject(all.keySet(), publishers).forEach(r -> latest.put(r.getSubject(), r.getLatest()));
        // 포스팅 선수는 국내 기사가 MLB 이야기여야 보이므로, 보일 기사가 실제로 있는지와 그 시각을 최근 기사로 한 번 더 본다
        List<NewsListResponse.Subject> chips = all.values().stream()
                .filter(s -> latest.containsKey(s.nameKo()))
                .map(s -> s.withLatestAt(s.posting() ? latestMlbArticle(s.nameKo()) : latest.get(s.nameKo())))
                .filter(s -> s.latestAt() != null)
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
        Page page = page(scope, postingOnly, cursor, q);
        boolean top = cursor.equals(Cursor.TOP);
        // 알약 숫자와 검색 줄 수는 같은 셈(searchRows)이다. 한 요청 안에서 같은 말은 한 번만 센다: 알약을 눌러 연 첫 쪽은
        // 그 말을 알약 숫자로 이미 셌으니 다시 읽지 않고, 두 숫자가 어긋날 틈도 없다
        Map<String, Integer> counted = new HashMap<>();
        // 많이 나온 말은 첫 쪽에만(뉴스 탭 맨 위 알약 줄). 검색어가 있어도 준다: 알약을 눌러 거른 채로도 줄이 그대로 보여야
        // 한다(이슈 #165). 그래서 검색어와 상관없이, 이 쪽을 만들며 읽은 기사와 별개로 범위의 최근 기사만 센다
        List<NewsListResponse.Topic> topics = top
                ? withSearchCounts(NewsTopics.count(recentGroups(scope, postingOnly), ownTeamWords(wanted, all)),
                        scope, postingOnly, counted)
                : List.of();
        // 검색 줄 수도 첫 쪽에만(이슈 #183). 웹은 둘째 쪽부터 첫 쪽 값을 그대로 쓴다
        Integer queryCount = top && q != null ? searchRows(scope, postingOnly, q, counted) : null;
        return new NewsListResponse(chips, wanted, page.items(), page.next(), q, topics, queryCount);
    }

    /**
     * 고른 선수의 지금 구단 이름들(자주 나온 말에서 뺀다, 이슈 #165). 전체 보기, 선수 번호가 없는 포스팅 선수, 소속을 모르는
     * 선수면 없다. 소속이 끝난 선수(FA)도 없다: 마지막 구단도 행선지 후보라 그 이름이 나온 기사는 걸러 볼 만하다
     */
    private List<String> ownTeamWords(String wanted, Map<String, NewsListResponse.Subject> all) {
        NewsListResponse.Subject subject = wanted == null ? null : all.get(wanted);
        if (subject == null || subject.playerId() == null) {
            return List.of();
        }
        return players.findById(subject.playerId())
                .flatMap(affiliations::findByPlayer)
                .filter(a -> !a.isEnded() && a.getTeam() != null)
                .map(Affiliation::getTeam)
                .map(team -> NewsTopics.teamWords(team.getAbbreviation()))
                .orElse(List.of());
    }

    /**
     * 최근 기사에서 고른 말의 숫자를 그 말로 검색하면 나오는 줄 수로 바꾼다(이슈 #167). 말은 요즘 화제라 최근 기사에서 고르지만,
     * 알약을 누르면 기사 전체에서 찾으므로 숫자도 전체에서 센다(최근 수만 보이면 기사가 TOPIC_ROWS를 넘은 뒤 'FA 17'을 눌러
     * 25줄이 나오는 식으로 어긋난다). 순서는 보이는 숫자 순(같으면 최근 순)
     */
    private List<NewsListResponse.Topic> withSearchCounts(List<NewsListResponse.Topic> recent, List<String> scope,
                                                          Set<String> postingOnly, Map<String, Integer> counted) {
        return recent.stream()
                .map(t -> new NewsListResponse.Topic(t.word(), searchRows(scope, postingOnly, t.word(), counted)))
                .sorted(Comparator.comparingInt(NewsListResponse.Topic::count).reversed())
                .toList();
    }

    /**
     * 이 말로 검색하면 나오는 줄 수: 검색 결과를 끝까지 넘겨 본 줄 수(같은 기사는 한 줄)다. 알약 숫자(withSearchCounts)와 검색
     * 줄 수(queryCount)를 이 하나로 세서, 알약 'FA 21'을 눌러 연 검색(q=FA)도 21이다(이슈 #183). counted는 한 요청 안에서 센
     * 값(소문자 말 → 줄 수)이다. 검색은 대소문자를 가리지 않으므로(likePattern·NewsTopics.mentions) 'FA'와 'fa'는 같은 값이다.
     * <p>
     * matching이 읽는 MAX_ROWS 상한에 걸리면(범위에서 DB가 제목에 이 글자가 든다고 고른 기사가 MAX_ROWS건보다 많으면. 영문 말은
     * 낱말 단위로 거르기 전이라 'FA'면 fans·DFA 같은 제목도 상한 숫자에 든다) 최근 MAX_ROWS건만
     * 묶어 센 값이 된다. 쪽 넘기기는 쪽마다 커서에서 다시 읽어 끝까지 가므로, 그때는 끝까지 넘겨 본 줄 수보다 적다(읽은 끝에
     * 걸친 묶음은 사본을 이어 주던 기사가 빠져 두 줄로 셀 수도 있다). 알약과 검색 줄 수는 그래도 같은 값이다.
     * 운영은 아직 멀었다: 11-18 뉴스 탭 전체(검색어 없음)가 123줄(사본까지 135건)이다.
     */
    private int searchRows(List<String> scope, Set<String> postingOnly, String word, Map<String, Integer> counted) {
        return counted.computeIfAbsent(word.toLowerCase(Locale.ROOT),
                key -> SameArticles.group(matching(scope, postingOnly, word)).size());
    }

    /**
     * 범위에서 검색어가 제목에 있는 기사 모두(목록과 같은 규칙으로 거른 것, 최대 MAX_ROWS건을 읽는다). 검색 결과를 끝까지
     * 넘겨 본 것과 같은 기사들이다(상한에 걸리면 최근 MAX_ROWS건까지, searchRows)
     */
    private List<NewsArticle> matching(List<String> scope, Set<String> postingOnly, String q) {
        String pattern = likePattern(q);
        List<NewsArticle> out = new ArrayList<>();
        Cursor read = Cursor.TOP;
        for (int rows = 0; rows < MAX_ROWS; ) {
            List<NewsArticle> batch = news.findPageMatching(scope, publishers, read.at(), read.id(), pattern,
                    PageRequest.of(0, BATCH));
            rows += batch.size();
            batch.stream()
                    .filter(a -> kept(a, postingOnly))
                    .filter(a -> matches(a, q))
                    .forEach(out::add);
            if (batch.size() < BATCH) {
                break;
            }
            read = Cursor.of(batch.getLast());
        }
        return out;
    }

    /** 범위의 최근 기사(최대 TOPIC_ROWS건, 목록과 같은 규칙으로 거른 것)를 같은 기사끼리 묶은 것 */
    private List<SameArticles.Group> recentGroups(List<String> scope, Set<String> postingOnly) {
        List<NewsArticle> recent = news.findPage(scope, publishers, Cursor.TOP.at(), Cursor.TOP.id(),
                        PageRequest.of(0, TOPIC_ROWS))
                .stream()
                .filter(a -> kept(a, postingOnly))
                .toList();
        return SameArticles.group(recent);
    }

    /** 검색어 → LIKE 패턴: 소문자로, '!'·'%'·'_'는 '!'로 막아 글자 그대로 찾게 하고 앞뒤에 '%' */
    static String likePattern(String q) {
        String escaped = q.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + escaped + "%";
    }

    private record Page(List<NewsListResponse.Item> items, String next) {
    }

    private Page page(List<String> scope, Set<String> postingOnly, Cursor cursor, String q) {
        // 검색어는 DB에서 글자 포함(LIKE)으로 먼저 고르고, 영문 말(FA 등)은 낱말 단위로 한 번 더 거른다(NewsTopics.mentions)
        String pattern = q == null ? null : likePattern(q);
        // 첫 쪽이 아니면 커서보다 NEAR만큼 늦은 데서부터 다시 읽는다(앞 쪽에서 안 보인 묶음의, 커서보다 늦은 사본까지 묶으려고)
        Cursor read = cursor.equals(Cursor.TOP) ? cursor : new Cursor(cursor.at().plus(SameArticles.NEAR), Long.MAX_VALUE);
        List<NewsArticle> kept = new ArrayList<>();
        List<SameArticles.Group> groups = List.of();
        int rows = 0;
        boolean exhausted;
        boolean capped = false;
        while (true) {
            List<NewsArticle> batch = pattern == null
                    ? news.findPage(scope, publishers, read.at(), read.id(), PageRequest.of(0, BATCH))
                    : news.findPageMatching(scope, publishers, read.at(), read.id(), pattern, PageRequest.of(0, BATCH));
            rows += batch.size();
            exhausted = batch.size() < BATCH;
            if (!batch.isEmpty()) {
                read = Cursor.of(batch.getLast());
            }
            batch.stream()
                    .filter(a -> kept(a, postingOnly))
                    .filter(a -> q == null || matches(a, q))
                    .forEach(kept::add);
            capped = !exhausted && rows >= MAX_ROWS;
            // 기사가 한 쪽 줄 수도 안 되면 묶어 봐야 모자란다. 묶기는 기사 쌍을 모두 견주므로 필요할 때만 한다
            if (!exhausted && !capped && kept.size() <= PAGE) {
                continue;
            }
            groups = byEarliest(SameArticles.group(kept)).stream()
                    .filter(g -> earlier(earliest(g), cursor)) // 앞 쪽까지 보인 묶음은 버린다
                    .toList();
            if (exhausted || capped) {
                break;
            }
            // 줄이 한 쪽보다 많고, 이 쪽 마지막 줄의 같은 기사(NEAR 안)까지 다 읽었으면 그만 읽는다
            if (groups.size() > PAGE && !read.at().isAfter(earliest(groups.get(PAGE - 1)).getPublishedAt().minus(SameArticles.NEAR))) {
                break;
            }
        }
        if (capped) {
            // 덜 읽힌 사본이 있을 수 있는 묶음(읽은 끝에서 NEAR 안)은 이번 쪽에 보이지 않는다(다음 쪽에서 다 모아 보인다)
            Instant complete = read.at().plus(SameArticles.NEAR);
            groups = groups.stream().filter(g -> !earliest(g).getPublishedAt().isBefore(complete)).toList();
        }
        List<SameArticles.Group> shown = groups.subList(0, Math.min(PAGE, groups.size()));
        String next = null;
        if (!shown.isEmpty() && (groups.size() > PAGE || !exhausted)) {
            next = Cursor.of(earliest(shown.getLast())).toString();
        } else if (shown.isEmpty() && !exhausted) {
            // 상한까지 읽고도 보일 줄이 없다(거의 다 걸러졌다). 읽은 데까지 넘겨 다음 쪽에서 이어 읽는다
            next = read.toString();
        }
        return new Page(shown.stream().map(NewsListFinder::item).toList(), next);
    }

    /** 포스팅 선수의 지금 보일(MLB 이야기) 기사 중 가장 최근 발행 시각. 최근 기사만 보고, 없으면 null */
    private Instant latestMlbArticle(String nameKo) {
        return news.findPage(List.of(nameKo), publishers, Cursor.TOP.at(), Cursor.TOP.id(), PageRequest.of(0, BATCH))
                .stream()
                .filter(a -> aiPublishers.contains(a.getPublisher()) || MlbContext.mentions(a.getTitle()))
                .findFirst()
                .map(NewsArticle::getPublishedAt)
                .orElse(null);
    }

    /** a가 커서 자리보다 오래됐나(발행 시각, 같으면 번호. 쿼리의 순서와 같다) */
    private static boolean earlier(NewsArticle a, Cursor cursor) {
        int byTime = a.getPublishedAt().compareTo(cursor.at());
        return byTime < 0 || (byTime == 0 && a.getId() < cursor.id());
    }

    /** 이 기사의 제목(원제·한국어 제목)에 검색어가 있나(NewsTopics.mentions 규칙). 요약은 보지 않는다(목록에 안 보인다) */
    private static boolean matches(NewsArticle a, String q) {
        return NewsTopics.mentions(a.getTitle(), q) || NewsTopics.mentions(a.getTitleKo(), q);
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

    /**
     * 고를 수 있는 선수: 포스팅 선수(파일 순서) 먼저, 그다음 명단 순. 이름은 기사에 저장된 그대로다(NewsCollector.subjects와
     * 같다: 포스팅 이름은 그대로, 명단 이름은 앞뒤 공백을 뗀다). 같은 이름이면 포스팅 선수로 두고 선수 번호만 명단에서 가져온다
     */
    private Map<String, NewsListResponse.Subject> subjects() {
        Map<Long, Long> idByMlbId = new HashMap<>();
        Map<String, Long> idByName = new HashMap<>();
        for (Player player : players.findByMlbIdIsNotNull()) {
            idByMlbId.put(player.getMlbId(), player.getId());
            if (player.getNameKo() != null) {
                idByName.putIfAbsent(player.getNameKo(), player.getId());
            }
        }
        Map<String, NewsListResponse.Subject> out = new LinkedHashMap<>();
        for (PostingCase posting : postings.current()) {
            out.put(posting.nameKo(), new NewsListResponse.Subject(posting.nameKo(), photos.postingPath(posting.slug()), null,
                    true, null));
        }
        List<RosterProperties.Entry> entries = roster.baseball() == null ? List.of() : roster.baseball();
        for (RosterProperties.Entry entry : entries) {
            // 국내 리그로 돌아간 선수는 해외파 소식이 아니다(기사도 모으지 않는다, NewsCollector)
            if (entry.nameKo() == null || entry.returnedKo() != null) {
                continue;
            }
            String name = entry.nameKo().trim();
            Long playerId = entry.mlbId() != null ? idByMlbId.get(entry.mlbId()) : idByName.get(name);
            NewsListResponse.Subject posting = out.get(name);
            out.put(name, posting != null
                    ? new NewsListResponse.Subject(name, posting.photoUrl(), playerId, true, null)
                    : new NewsListResponse.Subject(name, photos.mlbPath(entry.mlbId()), playerId, false, null));
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
