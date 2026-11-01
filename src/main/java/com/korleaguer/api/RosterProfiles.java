package com.korleaguer.api;

import com.korleaguer.config.KorleaguerProperties;
import com.korleaguer.domain.Player;
import com.korleaguer.ingest.RosterProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 명단(baseball.yml)에 사람이 적은 값(MLB 선수 번호, 한국 시절 소속)과 거기서 만드는 사진·MLB 페이지 주소.
 * 한글 이름으로 명단의 줄을 찾으므로 balldontlie와 매칭 전인 선수도 값이 나올 수 있다.
 */
@Component
class RosterProfiles {

    private static final String MLB_ID_PLACEHOLDER = "{mlbId}";
    private static final String MLB_PLAYER_PAGE = "https://www.mlb.com/player/";

    private final Map<String, RosterProperties.Entry> byNameKo = new LinkedHashMap<>();
    private final boolean officialPhotos;
    private final String photoTemplate;

    RosterProfiles(RosterProperties roster, KorleaguerProperties properties) {
        for (RosterProperties.Entry entry : roster.baseball()) {
            byNameKo.putIfAbsent(entry.nameKo(), entry);
        }
        this.officialPhotos = properties.images().officialPhotos();
        this.photoTemplate = properties.images().playerPhotoTemplate();
    }

    /** 공식 사진을 켰고(official-photos) MLB 선수 번호가 있을 때만 주소가 나온다. */
    String photoUrl(Player player) {
        Long mlbId = mlbId(player);
        if (!officialPhotos || mlbId == null || photoTemplate == null || photoTemplate.isBlank()) {
            return null;
        }
        return photoTemplate.replace(MLB_ID_PLACEHOLDER, String.valueOf(mlbId));
    }

    /** MLB 선수 번호만 있으면 나온다(사진 설정과 무관). */
    String mlbUrl(Player player) {
        Long mlbId = mlbId(player);
        return mlbId == null ? null : MLB_PLAYER_PAGE + mlbId;
    }

    String careerKo(Player player) {
        RosterProperties.Entry entry = byNameKo.get(player.getNameKo());
        return entry == null ? null : entry.careerKo();
    }

    private Long mlbId(Player player) {
        RosterProperties.Entry entry = byNameKo.get(player.getNameKo());
        return entry == null ? null : entry.mlbId();
    }
}
