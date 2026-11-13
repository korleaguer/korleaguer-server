-- 선수 화면의 'MLB 안 위치'와 지난 시즌 표(이슈 #133). 새 표 두 개라 블루-그린 배포 중 옛 API와 겹쳐도 안전하다.
-- stat_group: HITTING / PITCHING, innings_outs: 이닝을 아웃 수로(50.2이닝 = 152)

-- 지금 시즌 MLB 전체 선수와 견준 위치. 하루(UTC 날짜) 한 번 MLB 리그 전체 sabermetrics·시즌 합계를 받아 우리 선수 줄만 덮어쓴다.
-- 그날 리그 기록에 없는 선수(시즌 전, 마이너에만 있음)도 줄을 남겨 '오늘 받았음' 표시로 쓴다(값은 null).
-- *_runs: MLB가 준 득점 값(fielding_runs = 수비 + 포지션 보정), strikeout_rate·walk_rate: 상대 타자 중 삼진·볼넷 비율(%)
-- pool_size: 견준 MLB 선수 수(타자 100타석·투수 30이닝 이상), pct_*: 그중 이 선수보다 값이 나쁜 비율(1~99, 높을수록 좋다).
-- 표본이 적으면(타자 50타석·투수 10이닝 미만) pct_*는 null이다.
create table player_league_position (
    id                 bigint      not null auto_increment primary key,
    player_id          bigint      not null,
    season             int         not null,
    stat_group         varchar(10) not null,
    plate_appearances  int,
    innings_outs       int,
    wrc_plus           double,
    war                double,
    batting_runs       double,
    base_running_runs  double,
    fielding_runs      double,
    era_minus          double,
    fip_minus          double,
    strikeout_rate     double,
    walk_rate          double,
    pool_size          int,
    pct_overall        int,
    pct_batting        int,
    pct_base_running   int,
    pct_fielding       int,
    pct_run_prevention int,
    pct_strikeouts     int,
    pct_walks          int,
    fetched_at         datetime(6) not null,
    constraint uk_player_league_position unique (player_id, season, stat_group),
    constraint fk_player_league_position_player foreign key (player_id) references player (id)
);

-- 선수의 MLB 시즌별 기록(지난 시즌 표). MLB yearByYear를 하루 한 번 받아 덮어쓴다. 한 시즌 여러 팀이면 합친 줄 하나다.
-- team_ids: 그 시즌 뛴 MLB 구단 번호(뛴 순서, 쉼표로 잇는다), batting_avg(타율)·ops·era: MLB가 준 값(모르면 null)
-- wrc_plus·era_minus·war: 지금 시즌은 리그 전체 sabermetrics에서, 지난 시즌은 선수별 sabermetrics를 한 번만 물어 채운다.
-- metrics_fetched_at: 지난 시즌 지표를 물은 때(null이면 아직 묻지 않았다)
create table player_career_season (
    id                 bigint      not null auto_increment primary key,
    player_id          bigint      not null,
    season             int         not null,
    stat_group         varchar(10) not null,
    team_ids           varchar(100),
    games              int,
    plate_appearances  int,
    batting_avg        double,
    home_runs          int,
    ops                double,
    innings_outs       int,
    era                double,
    strikeouts         int,
    wrc_plus           double,
    era_minus          double,
    war                double,
    fetched_at         datetime(6) not null,
    metrics_fetched_at datetime(6),
    constraint uk_player_career_season unique (player_id, season, stat_group),
    constraint fk_player_career_season_player foreign key (player_id) references player (id)
);
