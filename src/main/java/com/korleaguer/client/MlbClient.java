package com.korleaguer.client;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * MLB 공식 Stats API(statsapi.mlb.com)에서 필요한 호출만 모은 인터페이스. 키는 필요 없다.
 * 실제 HTTP 구현(HttpMlbClient)과 샘플 구현(SampleMlbClient)이 같은 모양으로 답한다.
 * 화면용 API는 이 클라이언트를 부르지 않고 DB만 읽는다. 부르는 곳은 수집기(ingest)뿐이다.
 * 호출을 더할 때는 여기에 메서드를 두고, HttpMlbClient에서는 get(...) 도우미로 부른다(요청 제한·오류 처리가 같이 따라온다).
 * <p>
 * 모든 호출은 403/429면 MlbBlockedException(이번 실행의 MLB 호출을 멈추라는 뜻), 그 밖의 실패는 MlbApiException을 던진다.
 */
public interface MlbClient {

    /** 선수 여러 명과 각자의 지금 소속 팀(currentTeam). 없는 번호는 빠진다. */
    List<MlbPerson> getPeople(Collection<Long> personIds);

    /** 선수의 이동 기록(콜업·강등·부상자 명단 등). startDate~endDate(양 끝 포함) */
    List<MlbTransaction> getTransactions(long personId, LocalDate startDate, LocalDate endDate);

    /** 팀 한 곳과 그 팀의 리그 단계(sport). 없으면 null */
    MlbTeam getTeam(long teamId);

    /** 선수의 한 시즌 경기별 기록(정규시즌). 한 번에 시즌 전 경기가 온다. */
    List<MlbGameLogSplit> getGameLog(long personId, Group group, int season);

    /** 선수의 한 시즌 마이너리그(트리플A ~ 루키) 경기별 기록(정규시즌). 타격·투구를 한 번에 묻는다. */
    MlbMinorGameLog getMinorGameLog(long personId, int season);

    /** 선수의 시즌별 메이저 합계(정규시즌, 전 시즌). 여러 팀에서 뛴 시즌은 팀별 줄과 합친 줄이 같이 온다. */
    List<MlbSeasonSplit> getYearByYear(long personId, Group group);

    /** 선수의 한 시즌 메이저 고급 지표(wRC+·ERA-·WAR, 정규시즌). 그 시즌 메이저 기록이 없으면 빈 목록 */
    List<MlbSeasonSplit> getSabermetrics(long personId, Group group, int season);

    /**
     * 한 시즌 MLB 전체 선수의 시즌 합계(stats=season, 정규시즌). 선수마다 한 줄이다(여러 팀이면 합친 줄).
     * 타격이면 타석, 투구면 이닝·상대 타자·삼진·볼넷이 있다. 그 시즌 기록이 아직 없으면 빈 목록
     */
    List<MlbSeasonSplit> getLeagueSeasonStats(Group group, int season);

    /** 한 시즌 MLB 전체 선수의 고급 지표(stats=sabermetrics, 정규시즌). 선수마다 한 줄이다. 기록이 없으면 빈 목록 */
    List<MlbSeasonSplit> getLeagueSabermetrics(Group group, int season);

    /** 팀들의 한 시즌 메이저 일정(시범경기·포스트시즌 포함). 점수와 상태도 들어 있다. */
    List<MlbScheduleGame> getSeasonSchedule(Collection<Long> teamIds, int season);

    /** 팀들의 메이저 일정 중 공식 날짜(미국 날짜)가 startDate~endDate인 경기 */
    List<MlbScheduleGame> getSchedule(Collection<Long> teamIds, LocalDate startDate, LocalDate endDate);

    /**
     * 경기 번호로 그 경기들의 일정(시작 시각·상태·점수). 마이너 경기도 된다. 팀에는 지명(franchiseName)까지 온다.
     * 없는 번호는 빠지고, 연기된 경기는 같은 번호가 두 번 올 수 있다. 많으면 나눠 묻는다(HttpMlbClient: 한 번에 40경기).
     */
    List<MlbScheduleGame> getGames(Collection<Long> gamePks);

    /** 경기 박스스코어(선수별 기록, 타순, 나온 투수 순서). 없으면 null */
    MlbBoxscore getBoxscore(long gamePk);

    /** 한 시즌의 날짜(정규시즌 개막일·종료일, 포스트시즌 종료일). 아직 발표되지 않은 해면 null */
    MlbSeason getSeason(int season);

    /** 지금까지 보낸 요청 수(수집 기록용) */
    long requestCount();

    /** 기록 묶음: 타격 / 투구 */
    enum Group {
        HITTING,
        PITCHING;

        /** API의 group 값(hitting / pitching) */
        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
