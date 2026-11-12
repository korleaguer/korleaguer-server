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

/**
 * 우리가 호스팅하는 사진의 이름과 원본 주소 목록. 여기에 없는 이름은 받지도 주지도 않는다(아무 주소나 대신 받아 주지 않게).
 * <ul>
 *   <li>mlb-{MLB 선수 번호}: 명단(baseball.yml)의 mlb-id로 만든 MLB 공식 사진(korleaguer.images.player-photo-template)</li>
 *   <li>kbo-{slug}: 포스팅 선수(posting.yml)의 photo-url(현 소속 KBO 구단 사진)</li>
 *   <li>kbo-{KBO 선수 번호}-{시즌}: MLB 사진이 없는 지난 포스팅 선배의 포스팅 당시 KBO 공식 사진
 *       (korleaguer.images.kbo-photo-template)</li>
 *   <li>career-{KBO 선수 번호}-{시즌}: 명단 선수의 과거 소속팀(KBO) 시절 공식 사진(baseball.yml career-photos)을
 *       배경 없는 MLB 사진(mlb-…, headshot/silo)과 같은 틀(투명 바탕, 같은 얼굴 크기·위치)로 맞춘 편집본(PNG).
 *       선수 페이지에서 지금 MLB 사진과 넘겨 보므로 원 안 얼굴 크기·배경·선명도가 같아야 한다</li>
 *   <li>posting-{MLB 선수 번호}-{시즌}: 지난 포스팅 선배의 계약 구단 시절 MLB 공식 사진(posting.yml history의 photo-url)을
 *       배경을 지워 흰 바탕에 놓고 얼굴 크기를 포스팅 선수 KBO 사진에 맞춘 편집본</li>
 * </ul>
 * 편집본(career-…, posting-…)은 원본에서 받지 않고 우리가 만든 파일(src/main/resources/photos/, tools/posting-photo)을
 * 그대로 준다. 편집본이 빠졌으면 서버가 뜨지 않는다(원본을 그대로 보이면 원 안 배경·크기가 제각각이라).
 * 공식 사진을 끄면(korleaguer.images.official-photos=false) 목록이 비고, 응답의 photoUrl도 모두 null이다.
 * 화면은 /photos/{이름}으로 받는다(웹이 /api/photos/{이름}으로 넘긴다).
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

    private final Map<String, String> sources;

    public PhotoSources(RosterProperties roster, Postings postings, KorleaguerProperties properties) {
        Map<String, String> all = new LinkedHashMap<>();
        KorleaguerProperties.Images images = properties.images();
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
            for (PostingCase posting : postings.current()) {
                if (posting.slug() != null && posting.photoUrl() != null) {
                    all.putIfAbsent(kboKey(posting.slug()), posting.photoUrl());
                }
            }
        }
        this.sources = Collections.unmodifiableMap(all);
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

    /** 포스팅 선수 사진의 우리 주소(/photos/kbo-si-hwan-roh). 사진이 없으면 null */
    public String postingPath(String slug) {
        return slug == null ? null : path(kboKey(slug));
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
        return sources.containsKey(key) ? WEB_PATH + key : null;
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

    static String kboKey(String slug) {
        return "kbo-" + slug;
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
