package com.korleaguer.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * 선수 한 명의 경기 한 개 상세. 수집(ingest)이 만들어 player_game_detail.payload에 이 모양 JSON으로 저장하고,
 * 화면용 API(GET /api/players/{id}/games/{gamePk})가 그대로 읽어 응답으로 준다. 웹 경기 달력에서 날짜를 누르면 뜨는 창에 쓴다.
 * 필드 이름이 곧 저장된 JSON이자 웹과의 약속이다(이름·모양을 바꾸면 이미 저장된 줄을 못 읽는다).
 *
 * @param role       선수 주 역할(HITTER면 plays가 그 선수 타석, PITCHER면 그 선수가 상대한 타자)
 * @param available  타석 기록을 받았는지. false면 수집이 아직 못 받았다(잠시 뒤 다시)
 * @param clips      타석마다 공 영상(Baseball Savant)을 물을 수 있는지(메이저 경기이고 영상이 켜져 있을 때)
 * @param plays      타석(투수면 상대 타자) 순서대로
 * @param highlights 그 선수가 나온 MLB 하이라이트 영상(올라온 순서). 영상이 꺼져 있거나 없으면 빈 목록
 */
public record GameDetail(
        long playerId,
        long gamePk,
        @Schema(allowableValues = {"HITTER", "PITCHER"}) String role,
        boolean available,
        boolean clips,
        List<GamePlay> plays,
        List<GameHighlight> highlights) {

    /** 아직 받지 못했을 때(수집 전) */
    public static GameDetail unavailable(long playerId, long gamePk, String role) {
        return new GameDetail(playerId, gamePk, role, false, false, List.of(), List.of());
    }

    /**
     * 타석 하나.
     *
     * @param top          초(원정 공격)면 true
     * @param resultKo     한국어 결과("홈런", "중견수 뜬공", "헛스윙 삼진", "우전 안타" …)
     * @param eventType    MLB 결과 종류(home_run, field_out …). 웹이 색을 고를 때 쓴다
     * @param rbi          타점(투수면 그 타석에 준 점수)
     * @param opponentName 상대(타자면 투수, 투수면 타자) 영어 이름
     * @param balls        타석이 끝났을 때 볼 수
     * @param strikes      타석이 끝났을 때 스트라이크 수
     * @param pitches      그 타석 공 수
     * @param pitchKo      마지막 공 구종("직구", "슬라이더" …). 모르면 null
     * @param pitchKmh     마지막 공 구속(km/h). 모르면 null
     * @param exitKmh      타구 속도(km/h). 공을 치지 않았으면 null
     * @param launchAngle  발사각(도)
     * @param distanceM    비거리(m)
     * @param playId       마지막 공 번호(공 영상을 물을 때 쓴다). 모르면 null
     */
    public record GamePlay(
            int inning,
            boolean top,
            String resultKo,
            String eventType,
            int rbi,
            @Nullable String opponentName,
            @Nullable Integer balls,
            @Nullable Integer strikes,
            int pitches,
            @Nullable String pitchKo,
            @Nullable Integer pitchKmh,
            @Nullable Integer exitKmh,
            @Nullable Integer launchAngle,
            @Nullable Integer distanceM,
            @Nullable String playId) {
    }

    /**
     * MLB 하이라이트 영상 하나(MLB 서버의 주소 그대로, 우리는 저장하지 않는다).
     *
     * @param title       영어 제목("Jung Hoo Lee's solo home run (10)")
     * @param titleKo     한국어 제목("솔로 홈런 (시즌 10호)", 선수 이름은 뺀다). 아직 못 옮겼거나 AI가 꺼져 있으면 null(이슈 #199)
     * @param durationSec 길이(초). 모르면 null
     * @param mp4Url      mp4 주소. 없으면 null
     * @param hlsUrl      HLS(.m3u8) 주소. 없으면 null
     * @param imageUrl    대표 사진(960x540, 없으면 640x360). 없으면 null
     */
    public record GameHighlight(
            String title,
            @Nullable String titleKo,
            @Nullable Integer durationSec,
            @Nullable String mp4Url,
            @Nullable String hlsUrl,
            @Nullable String imageUrl) {

        public GameHighlight withTitleKo(String titleKo) {
            return new GameHighlight(title, titleKo, durationSec, mp4Url, hlsUrl, imageUrl);
        }
    }
}
