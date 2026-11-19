package com.korleaguer.photo;

import com.korleaguer.config.KorleaguerProperties;
import com.korleaguer.ingest.RosterProperties;
import com.korleaguer.posting.PastPosting;
import com.korleaguer.posting.PostingCase;
import com.korleaguer.posting.Postings;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 우리가 호스팅하는 사진의 이름과 원본 주소 목록. 여기에 없는 이름은 받지도 주지도 않는다(아무 주소나 대신 받아 주지 않게).
 * <ul>
 *   <li>mlb-{MLB 선수 번호}: 명단(baseball.yml)의 mlb-id로 만든 MLB 공식 사진(korleaguer.images.player-photo-template)</li>
 *   <li>kbo-{slug}-{사진 시즌}: 포스팅 선수(posting.yml current)의 현 소속 KBO 구단 공식 사진(photo-url)을 흰 바탕 정사각형에
 *       놓은 편집본(얼굴 크기·위치가 아래 posting-… 선배 사진과 같은 틀). KBO 사진 서버 원본(94×118)은 홈 76px 원에서
 *       흐려서 같은 사진의 큰 판으로 만든다. 이름에 사진 시즌을 넣어, 사진을 바꾸면 주소도 바뀌게 한다
 *       (사진을 하루 동안 저장해 두는 브라우저·중간 캐시에 옛 사진이 남지 않게)</li>
 *   <li>kbo-{KBO 선수 번호}-{시즌}: MLB 사진이 없는 지난 포스팅 선배의 포스팅 당시 KBO 공식 사진
 *       (korleaguer.images.kbo-photo-template)</li>
 *   <li>career-{KBO 선수 번호}-{시즌}: 명단 선수의 과거 소속팀(KBO) 시절 공식 사진(baseball.yml career-photos)을
 *       배경 없는 MLB 사진(mlb-…, headshot/silo)과 같은 틀(투명 바탕, 같은 얼굴 크기·위치)로 맞춘 편집본(PNG).
 *       선수 페이지에서 지금 MLB 사진과 넘겨 보므로 원 안 얼굴 크기·배경·선명도가 같아야 한다</li>
 *   <li>posting-{MLB 선수 번호}-{시즌}: 지난 포스팅 선배의 계약 구단 시절 MLB 공식 사진(posting.yml history의 photo-url)을
 *       배경을 지워 흰 바탕에 놓고 얼굴 크기·위치를 포스팅 선수 사진(노시환 KBO 공식 사진)과 같은 틀로 맞춘 편집본</li>
 * </ul>
 * 편집본(career-…, posting-…, 포스팅 선수 kbo-{slug}-{사진 시즌})은 원본에서 받지 않고 우리가 만든 파일
 * (src/main/resources/photos/, tools/posting-photo)을 그대로 준다. 편집본이 빠졌으면 서버가 뜨지 않는다
 * (원본을 그대로 보이면 원 안 배경·크기·선명도가 제각각이라).
 * 공식 사진을 끄면(korleaguer.images.official-photos=false) 목록이 비고, 응답의 photoUrl도 모두 null이다.
 * 화면은 /photos/{이름}으로 받는다(웹이 /api/photos/{이름}으로 넘긴다).
 * 사진 주소 뒤에는 저장된 내용 해시(sha256) 앞 8자를 ?v=로 붙인다(/photos/mlb-808982?v=1a2b3c4d). 같은 이름의 사진 내용이
 * 바뀌면 주소가 바뀌어, 버전이 붙은 요청은 오래 저장해 둬도 옛 사진이 남지 않는다. 아직 받지 못한 사진은 버전 없이 나간다.
 */
@Component
public class PhotoSources {

    public static final String WEB_PATH = "/photos/";
    /** 원본에서 받지 않고 우리 파일(클래스패스)을 그대로 주는 사진의 원본 주소 앞머리 */
    public static final String BUNDLED = "classpath:";
    private static final String BUNDLED_DIR = "photos/";
    private static final String MLB_ID_PLACEHOLDER = "{mlbId}";
    private static final String KBO_ID_PLACEHOLDER = "{kboId}";
    private static final String SEASON_PLACEHOLDER = "{season}";

    /** 주소에 붙이는 내용 버전(해시 앞 글자 수) */
    public static final int VERSION_LENGTH = 8;

    private final Map<String, String> sources;
    private final boolean officialPhotos;
    /** 사진 이름 → 저장된 본문의 sha256. 우리 파일은 뜰 때 읽고, 받은 사진은 PhotoStore가 알려 준다 */
    private final Map<String, String> hashes = new ConcurrentHashMap<>();

    public PhotoSources(RosterProperties roster, Postings postings, KorleaguerProperties properties) {
        Map<String, String> all = new LinkedHashMap<>();
        KorleaguerProperties.Images images = properties.images();
        this.officialPhotos = images != null && images.officialPhotos();
        if (images != null && images.officialPhotos()) {
            String template = images.playerPhotoTemplate();
            if (template != null && !template.isBlank()) {
                for (RosterProperties.Entry entry : roster.baseball()) {
                    if (entry.mlbId() != null) {
                        all.putIfAbsent(mlbKey(entry.mlbId()), template.replace(MLB_ID_PLACEHOLDER,
                                String.valueOf(entry.mlbId())));
                    }
                }
            }
            // 명단 선수의 과거 소속팀(KBO) 시절 사진: MLB 배경 없는 사진과 같은 틀의 편집본(tools/posting-photo silo)
            for (RosterProperties.Entry entry : roster.baseball()) {
                if (entry.kboId() == null) {
                    continue;
                }
                for (RosterProperties.CareerPhoto photo : entry.careerPhotos()) {
                    if (photo.season() != null) {
                        String key = careerKey(entry.kboId(), photo.season());
                        all.putIfAbsent(key, bundledFile(key, ".png", "roster/baseball.yml " + entry.nameKo()
                                + " career-photos", "the season's KBO official photo with tools/posting-photo silo"));
                    }
                }
            }
            String kboTemplate = images.kboPhotoTemplate();
            if (kboTemplate != null && !kboTemplate.isBlank()) {
                // 지난 포스팅 선배 중 MLB 사진을 적지 않은 선배: 포스팅된 겨울(season)의 KBO 공식 등록 사진
                for (PastPosting past : postings.history()) {
                    if (past.photoUrl() == null && past.kboId() != null) {
                        all.putIfAbsent(kboSeasonKey(past.kboId(), past.season()),
                                kboUrl(kboTemplate, past.kboId(), past.season()));
                    }
                }
            }
            // 지난 포스팅 선배: 계약한 MLB 구단 시절 MLB 공식 사진의 편집본(흰 바탕, 포스팅 선수 사진과 같은 얼굴 크기)
            for (PastPosting past : postings.history()) {
                if (past.photoUrl() != null) {
                    String key = pastPostingKey(past.mlbId(), past.photoSeason());
                    all.putIfAbsent(key, bundledFile(key, ".jpg", "roster/posting.yml posting.history "
                            + past.nameKo(), "photo-url with tools/posting-photo cut"));
                }
            }
            // 포스팅 선수: 현 소속 KBO 구단 공식 사진의 편집본(선배 사진과 같은 흰 바탕 정사각형 틀).
            // photo-url이 있으면 slug·photo-season도 있다(Postings가 yml을 검사하고 PostingCase 생성자가 막는다)
            for (PostingCase posting : postings.current()) {
                if (posting.photoUrl() != null) {
                    String key = kboKey(posting.slug(), posting.photoSeason());
                    all.putIfAbsent(key, bundledFile(key, ".jpg", "roster/posting.yml posting.current "
                            + posting.nameKo(), "photo-url with tools/posting-photo cut"));
                }
            }
        }
        this.sources = Collections.unmodifiableMap(all);
        all.forEach((key, source) -> {
            if (bundled(source)) {
                hashes.put(key, PhotoStore.sha256(readResource(source.substring(BUNDLED.length()))));
            }
        });
    }

    /**
     * 목록이 정상인가: 공식 사진을 켰고(official-photos) 목록이 비지 않았다. 끄면 목록이 비는 게 정상이고,
     * 켰는데 비었으면 설정이 잘못된 것이라 어느 쪽이든 이 목록으로 저장본을 정리하면 안 된다
     */
    public boolean listUsable() {
        return officialPhotos && !sources.isEmpty();
    }

    /** 저장된 사진의 본문 해시를 알려 준다(PhotoStore가 받거나 저장본을 확인할 때마다). 주소의 ?v=가 이 값을 따른다 */
    void remember(String key, String sha256) {
        if (sources.containsKey(key) && sha256 != null) {
            hashes.put(key, sha256);
        }
    }

    /** 이 사진 내용의 버전(sha256 앞 8자). 아직 본문을 모르면 빈 값 */
    public Optional<String> version(String key) {
        String sha = hashes.get(key);
        return sha == null ? Optional.empty() : Optional.of(sha.substring(0, VERSION_LENGTH));
    }

    private static byte[] readResource(String resource) {
        try (java.io.InputStream in = PhotoSources.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("bundled photo is missing: " + resource);
            }
            return in.readAllBytes();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("cannot read bundled photo " + resource, e);
        }
    }

    /** 이 이름의 원본 주소. 목록에 없으면 빈 값 */
    public Optional<String> source(String key) {
        return Optional.ofNullable(sources.get(key));
    }

    /** 이름 → 원본 주소 전부(명단 순서, 그다음 포스팅 선수) */
    public Map<String, String> all() {
        return sources;
    }

    /** MLB 선수 사진의 우리 주소(/photos/mlb-808982). 사진을 껐거나 번호가 없으면 null */
    public String mlbPath(Long mlbId) {
        return mlbId == null ? null : path(mlbKey(mlbId));
    }

    /** 포스팅 선수 사진 편집본의 우리 주소(/photos/kbo-si-hwan-roh-2026). 사진을 껐거나 없으면 null */
    public String postingPath(PostingCase posting) {
        return posting.photoUrl() == null ? null : path(kboKey(posting.slug(), posting.photoSeason()));
    }

    /** 명단 선수의 과거 소속팀(KBO) 시절 사진 편집본의 우리 주소(/photos/career-67341-2023). 사진을 껐거나 목록에 없으면 null */
    public String careerPath(Long kboId, Integer season) {
        return kboId == null || season == null ? null : path(careerKey(kboId, season));
    }

    /** 지난 포스팅 선배의 포스팅 당시 KBO 공식 사진의 우리 주소(/photos/kbo-67341-2023). 사진을 껐거나 목록에 없으면 null */
    public String kboSeasonPath(Long kboId, Integer season) {
        return kboId == null || season == null ? null : path(kboSeasonKey(kboId, season));
    }

    /** 지난 포스팅 선배 사진 편집본의 우리 주소(/photos/posting-673490-2024). 사진을 껐거나 목록에 없으면 null */
    public String pastPostingPath(Long mlbId, Integer season) {
        return mlbId == null || season == null ? null : path(pastPostingKey(mlbId, season));
    }

    /** 원본에서 받지 않고 우리 파일을 그대로 주는 사진인가(원본 주소가 classpath:…) */
    public static boolean bundled(String source) {
        return source.startsWith(BUNDLED);
    }

    /**
     * 시즌이 정해진 사진(kbo-67341-2023)인가. 그 시즌의 모습이라 바뀔 일이 없으므로
     * 한 번 받으면 원본에 다시 묻지 않는다(선수가 팀을 옮겨 원본 '현재 사진'이 바뀌어도 그 시즌 사진을 지킨다).
     */
    public static boolean seasonFixed(String key) {
        return key.matches("kbo-\\d+-\\d{4}");
    }

    private String path(String key) {
        if (!sources.containsKey(key)) {
            return null;
        }
        return version(key).map(v -> WEB_PATH + key + "?v=" + v).orElse(WEB_PATH + key);
    }

    static String mlbKey(Long mlbId) {
        return "mlb-" + mlbId;
    }

    static String pastPostingKey(Long mlbId, int season) {
        return "posting-" + mlbId + "-" + season;
    }

    static String careerKey(Long kboId, int season) {
        return "career-" + kboId + "-" + season;
    }

    static String kboSeasonKey(Long kboId, int season) {
        return "kbo-" + kboId + "-" + season;
    }

    static String kboKey(String slug, int season) {
        return "kbo-" + slug + "-" + season;
    }

    /** 우리가 만든 편집본(src/main/resources/photos/{이름}{확장자})의 원본 주소. 파일이 없으면 서버가 뜨지 않는다 */
    private static String bundledFile(String key, String extension, String owner, String howToMake) {
        String resource = BUNDLED_DIR + key + extension;
        if (PhotoSources.class.getClassLoader().getResource(resource) == null) {
            throw new IllegalStateException(owner + ": edited photo src/main/resources/" + resource
                    + " is missing (make it from " + howToMake + ")");
        }
        return BUNDLED + resource;
    }

    private static String kboUrl(String template, Long kboId, int season) {
        return template.replace(KBO_ID_PLACEHOLDER, String.valueOf(kboId))
                .replace(SEASON_PLACEHOLDER, String.valueOf(season));
    }
}
