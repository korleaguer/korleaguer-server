package com.korleaguer.api;

import com.korleaguer.domain.Level;
import com.korleaguer.domain.Role;

import java.time.LocalDate;
import java.util.List;

/**
 * GET /api/players/{id}. 타자면 pitching이, 투수면 hitting이 null이다.
 * moves는 MLB 공식 API의 이동 기록 중 팬에게 의미 있는 것만, 최신순이다(없으면 빈 목록).
 * 이슈 #133에서 더한 칸(옛 화면은 무시해도 된다):
 * <ul>
 *   <li>saber: 지금 시즌 MLB 안 위치(PlayerSaber, 타자·투수 모양이 다르다). 아직 받지 않았거나 그 시즌 MLB 기록이 없으면 null</li>
 *   <li>seasons: MLB 시즌별 기록(CareerSeason, 최신 시즌부터). 아직 받지 않았으면 빈 목록</li>
 *   <li>news: 이 선수 기사(늦은 순, 같은 기사는 한 줄, 최대 10건). 비시즌 성적표의 기사와 같은 모양·규칙이다. 없으면 빈 목록</li>
 * </ul>
 */
public record PlayerPageResponse(
        String dataMode,
        boolean statsAvailable,
        int season,
        PlayerInfo player,
        HitterSeason hitting,
        PitcherSeason pitching,
        List<GameLine> games,
        List<Move> moves,
        PlayerSaber saber,
        List<CareerSeason> seasons,
        List<PostingSummary.Headline> news) {

    /**
     * photoUrl·mlbUrl·careerKo는 명단(baseball.yml) 값이라 MLB에서 받기 전에도 나올 수 있다.
     * 생년월일·키·몸무게·투타·등번호·데뷔 연도는 MLB 선수 정보 값이라 받기 전이거나 값이 없으면 null이다.
     * age는 생년월일이 있으면 한국 시간 오늘 기준 만 나이, 없으면 MLB가 준 나이(currentAge)다.
     * batsThrowsKo는 "우투좌타"처럼 투구 먼저 쓴다.
     * level·levelTeam·freeAgent는 선수 목록(PlayerSummary)과 같다: 지금 메이저(MLB)인지 마이너(MINORS)인지,
     * MLB가 알려 준 지금 팀 이름, MLB 이동 기록상 FA인지. returnedKo도 목록과 같다(국내 리그로 돌아간 팀, 없으면 null).
     * careerPhotos는 과거 소속팀(KBO) 시절 공식 사진(오래된 팀부터, 없으면 빈 목록)이다.
     */
    public record PlayerInfo(
            long id,
            String nameKo,
            String photoUrl,
            String mlbUrl,
            String nameEn,
            Role role,
            String position,
            String positionKo,
            String teamKo,
            String teamAbbr,
            String teamEn,
            String birthPlace,
            String jersey,
            LocalDate birthDate,
            Integer age,
            Integer heightCm,
            Integer weightKg,
            String batsThrowsKo,
            Integer debutYear,
            String careerKo,
            Level level,
            String levelTeam,
            boolean freeAgent,
            String returnedKo,
            List<CareerPhoto> careerPhotos) {
    }

    /**
     * 과거 소속팀(KBO) 시절 공식 사진 한 장(명단 baseball.yml의 career-photos). 우리가 호스팅하는 주소다(/photos/career-67341-2023).
     * 지금 MLB 사진(배경 없는 사진)과 같은 틀(투명 바탕, 같은 얼굴 크기·위치)의 편집본이라 화면은 두 사진을 같은 방식으로 보인다.
     *
     * @param teamKo 팀 이름(예: "키움 히어로즈")
     * @param years  그 팀에서 뛴 해(예: "2017–2023")
     */
    public record CareerPhoto(String teamKo, String years, String photoUrl) {
    }

    /**
     * 이동 기록 한 줄. labelKo는 서버가 typeCode(와 설명 글)로 만든 짧은 한국어 이름이다(예: "메이저 콜업").
     * fromTeam·toTeam은 MLB 구단이면 한글 이름(예: "미네소타"), 마이너 팀 등은 MLB가 준 영어 이름 그대로이고 없으면 null.
     * toLevel은 옮겨 간 팀의 단계(MLB / MINORS), 모르면 null이다.
     */
    public record Move(
            LocalDate date,
            String typeCode,
            String labelKo,
            String fromTeam,
            String toTeam,
            Level toLevel) {
    }
}
