-- 리그 단계별 한 시즌 평균(이슈 #233). 수집기(ingest.LeagueAverageRefresher)가 MLB 팀별 시즌 합계
-- (teams/stats?stats=season&sportIds=…, 타격·투구 한 번씩)를 더해 저장하고, 화면용 API는 이 표만 읽는다(api.LeagueEnvironments).
-- 전에는 화면 응답을 계산할 때 받아 메모리에만 12시간 두었다(서버를 다시 띄우면 사라지고, 못 받으면 표준값으로 계산했다).
-- (season, level)마다 한 줄. level: MLB·AAA·AA·A+·A·ROK(minor_game_log.level과 같은 약어)
-- woba: 리그 wOBA(우리 가중치, support.Woba), runs_per_pa: 타석당 득점, era: 평균자책점. 반올림하지 않고 계산한 값 그대로 둔다
--   (화면 숫자가 전과 같게 나오도록).
-- fetched_at: 마지막으로 받은 시각(UTC). 지금 시즌 줄은 시즌 중에 하루 한 번 다시 받고, 끝난 시즌 줄은 다시 받지 않는다.
-- 새 표 하나라 블루-그린 배포 중 옛 API와 겹쳐도 안전하다(옛 API는 이 표를 모른다).
create table league_average (
    id          bigint      not null auto_increment primary key,
    season      int         not null,
    level       varchar(10) not null,
    woba        double      not null,
    runs_per_pa double      not null,
    era         double      not null,
    fetched_at  datetime(6) not null,
    constraint uk_league_average unique (season, level)
);
