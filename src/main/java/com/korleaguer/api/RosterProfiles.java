package com.korleaguer.api;

import com.korleaguer.domain.Player;
import com.korleaguer.ingest.RosterProperties;
import com.korleaguer.photo.PhotoSources;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 명단(baseball.yml)에 사람이 적은 값(MLB 선수 번호, 한국 시절 소속)과 거기서 만드는 사진·MLB 페이지 주소.
 * 한글 이름으로 명단의 줄을 찾으므로 MLB에서 선수 정보를 받기 전에도 값이 나올 수 있다.
 */
@Component
class RosterProfiles {

    private static final String MLB_PLAYER_PAGE = "https://www.mlb.com/player/";

    private final Map<String, RosterProperties.Entry> byNameKo = new LinkedHashMap<>();
    private final PhotoSources photos;

    RosterProfiles(RosterProperties roster, PhotoSources photos) {
        for (RosterProperties.Entry entry : roster.baseball()) {
            byNameKo.putIfAbsent(entry.nameKo(), entry);
        }
        this.photos = photos;
    }

    /**
     * 우리가 호스팅하는 사진 주소(/photos/mlb-808982, PhotoSources). 공식 사진을 켰고(official-photos)
     * MLB 선수 번호가 있을 때만 나온다. 원본(MLB 사진 서버)은 서버가 한 번 받아 둔다(PhotoStore).
     */
    String photoUrl(Player player) {
        return photos.mlbPath(mlbId(player));
    }

    /** MLB 선수 번호만 있으면 나온다(사진 설정과 무관). */
    String mlbUrl(Player player) {
        Long mlbId = mlbId(player);
        return mlbId == null ? null : MLB_PLAYER_PAGE + mlbId;
    }

    /** 과거 소속팀(KBO) 시절 공식 사진(명단 순서). 사진을 껐거나 적어 둔 사진이 없으면 빈 목록 */
    List<PlayerPageResponse.CareerPhoto> careerPhotos(Player player) {
        RosterProperties.Entry entry = byNameKo.get(player.getNameKo());
        if (entry == null) {
            return List.of();
        }
        return entry.careerPhotos().stream()
                .map(photo -> new PlayerPageResponse.CareerPhoto(photo.team(), photo.years(),
                        photos.careerPath(entry.kboId(), photo.season())))
                .filter(photo -> photo.photoUrl() != null)
                .toList();
    }

    String careerKo(Player player) {
        RosterProperties.Entry entry = byNameKo.get(player.getNameKo());
        return entry == null ? null : entry.careerKo();
    }

    /** 국내 리그로 돌아간 팀(명단의 returned-ko). 해외에서 뛰고 있으면 null */
    String returnedKo(Player player) {
        RosterProperties.Entry entry = byNameKo.get(player.getNameKo());
        return entry == null ? null : entry.returnedKo();
    }

    private Long mlbId(Player player) {
        RosterProperties.Entry entry = byNameKo.get(player.getNameKo());
        return entry == null ? null : entry.mlbId();
    }
}
