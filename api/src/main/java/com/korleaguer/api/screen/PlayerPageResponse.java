package com.korleaguer.api.screen;

import com.korleaguer.api.player.MoveEntry;
import com.korleaguer.api.player.PastTeamPhoto;

import com.korleaguer.api.article.PostingSummary;
import com.korleaguer.api.game.GameLine;
import com.korleaguer.api.game.MinorGameLine;
import com.korleaguer.api.season.CareerSeason;
import com.korleaguer.api.season.HitterSeason;
import com.korleaguer.api.season.PitcherSeason;
import com.korleaguer.api.season.PlayerSaber;

import com.korleaguer.domain.Level;
import com.korleaguer.domain.Role;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * GET /api/players/{id}. 타자면 pitching이, 투수면 hitting이 null이다.
 * generatedAt(이슈 #184)은 이 응답을 만든 시각(초 단위)으로, 홈(HomeResponse)·내 선수(MyPlayersResponse)와 뜻·꼴이 같다.
 * 응답을 캐시하므로 캐시에서 준 응답이면 처음 계산한 시각이다. 웹이 시즌 성적 제목 옆에 '기준 …'으로 보여,
 * 보고 있는 숫자를 언제 계산했는지 알 수 있게 한다.
 * minorHitting·minorPitching은 같은 시즌 마이너 경기 기록을 더한 것(홈 minorHitters·minorPitchers와 같은 계산)이고,
 * 마이너 경기가 없으면 null이다. 메이저 기록(hitting·pitching)과 따로 보여 줘야 작은 표본이 시즌 전체처럼 보이지 않는다.
 * moves는 MLB 공식 API의 이동 기록 중 팬에게 의미 있는 것만, 최신순이다(없으면 빈 목록). 팀 이름은 MLB 구단이면 한글
 * 이름, 마이너 팀 이름표(roster/minor-teams.yml)에 있는 팀이면 한국어 지명과 단계다(MoveEntry, 이슈 #183·#191).
 * 이슈 #133에서 더한 칸(옛 화면은 무시해도 된다):
 * <ul>
 *   <li>saber: 지금 시즌 MLB 안 위치(PlayerSaber, 타자·투수 모양이 다르다). 아직 받지 않았거나 그 시즌 MLB 기록이 없으면 null</li>
 *   <li>seasons: MLB 시즌별 기록(CareerSeason, 최신 시즌부터). 아직 받지 않았으면 빈 목록</li>
 *   <li>news: 이 선수 기사(늦은 순, 같은 기사는 한 줄, 최대 10건). 비시즌 성적표의 기사와 같은 모양·규칙이다. 없으면 빈 목록</li>
 * </ul>
 * minorGames(이슈 #145)는 같은 시즌 마이너 경기 줄(MinorGameLine, 최신 경기부터)이다. 웹이 games와 날짜순으로 섞어 '경기 기록'에
 * 보인다(마이너에서 잘해야 콜업되니 마이너에 오래 있는 선수의 소식도 보이게). 마이너 경기가 없으면 빈 목록이다.
 * statsAvailable은 늘 true다(MLB 공식 API는 경기별 선수 기록을 늘 준다). 웹 호환 때문에 남겨 둔다.
 */
public record PlayerPageResponse(
        String dataMode,
        boolean statsAvailable,
        int season,
        Instant generatedAt,
        PlayerInfo player,
        HitterSeason hitting,
        PitcherSeason pitching,
        HitterSeason minorHitting,
        PitcherSeason minorPitching,
        List<GameLine> games,
        List<MoveEntry> moves,
        PlayerSaber saber,
        List<CareerSeason> seasons,
        List<PostingSummary.Headline> news,
        List<MinorGameLine> minorGames) {

    /**
     * photoUrl·mlbUrl·careerKo는 명단(baseball.yml) 값이라 MLB에서 받기 전에도 나올 수 있다.
     * 생년월일·키·몸무게·투타·등번호·데뷔 연도는 MLB 선수 정보 값이라 받기 전이거나 값이 없으면 null이다.
     * age는 생년월일이 있으면 한국 시간 오늘 기준 만 나이, 없으면 MLB가 준 나이(currentAge)다.
     * batsThrowsKo는 "우투좌타"처럼 투구 먼저 쓴다.
     * level·levelTeam·freeAgent는 선수 목록(PlayerSummary)과 같다: 지금 메이저(MLB)인지 마이너(MINORS)인지,
     * MLB가 알려 준 지금 팀 이름, MLB 이동 기록상 FA인지. returnedKo도 목록과 같다(국내 리그로 돌아간 팀, 없으면 null).
     * careerPhotos는 과거 소속팀(KBO) 시절 공식 사진(오래된 팀부터, 없으면 빈 목록)이다.
     * photoTeamAbbr은 photoUrl 사진 속 유니폼 팀 약어(웹 사진 원 바탕색)다. 사진이 없으면 null이고, teamAbbr과 다를 수 있다.
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
            List<PastTeamPhoto> careerPhotos,
            String photoTeamAbbr) {
    }

}
