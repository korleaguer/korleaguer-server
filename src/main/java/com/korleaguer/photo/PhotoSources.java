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
 *   <li>kbo-{KBO 선수 번호}-{시즌}: 명단 선수의 과거 소속팀(KBO) 시절 공식 사진, 지난 포스팅 선배(posting.yml
 *       history의 kbo-id)의 포스팅 당시 사진(korleaguer.images.kbo-photo-template)</li>
 * </ul>
 * 공식 사진을 끄면(korleaguer.images.official-photos=false) 목록이 비고, 응답의 photoUrl도 모두 null이다.
 * 화면은 /photos/{이름}으로 받는다(웹이 /api/photos/{이름}으로 넘긴다).
 */
@Component
public class PhotoSources {

    public static final String WEB_PATH = "/photos/";
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
            String kboTemplate = images.kboPhotoTemplate();
            if (kboTemplate != null && !kboTemplate.isBlank()) {
                for (RosterProperties.Entry entry : roster.baseball()) {
                    if (entry.kboId() == null) {
                        continue;
                    }
                    for (RosterProperties.CareerPhoto photo : entry.careerPhotos()) {
                        if (photo.season() != null) {
                            all.putIfAbsent(careerKey(entry.kboId(), photo.season()),
                                    kboUrl(kboTemplate, entry.kboId(), photo.season()));
                        }
                    }
                }
                // 지난 포스팅 선배: 포스팅된 겨울(season)의 KBO 공식 등록 사진 = 포스팅 당시 구단의 마지막 시즌 사진.
                // 명단 선수의 과거 소속팀 사진과 이름이 같으면(이정후 2023 등) 하나만 받는다
                for (PastPosting past : postings.history()) {
                    if (past.kboId() != null) {
                        all.putIfAbsent(careerKey(past.kboId(), past.season()),
                                kboUrl(kboTemplate, past.kboId(), past.season()));
                    }
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

    /** 과거 소속팀(KBO) 시절 사진의 우리 주소(/photos/kbo-67341-2023). 사진을 껐거나 목록에 없으면 null */
    public String careerPath(Long kboId, Integer season) {
        return kboId == null || season == null ? null : path(careerKey(kboId, season));
    }

    private String path(String key) {
        return sources.containsKey(key) ? WEB_PATH + key : null;
    }

    static String mlbKey(Long mlbId) {
        return "mlb-" + mlbId;
    }

    static String careerKey(Long kboId, int season) {
        return "kbo-" + kboId + "-" + season;
    }

    static String kboKey(String slug) {
        return "kbo-" + slug;
    }

    private static String kboUrl(String template, Long kboId, int season) {
        return template.replace(KBO_ID_PLACEHOLDER, String.valueOf(kboId))
                .replace(SEASON_PLACEHOLDER, String.valueOf(season));
    }
}
