-- 선수의 시즌별 메이저 합계와 고급 지표(이슈 #118). 비시즌 홈의 '시즌 성적표' 판정(좋은 시즌·아쉬운 시즌 등)에 쓴다.
-- 끝난 시즌과 그 앞 3시즌을 MLB yearByYear(타석·이닝·선발)와 sabermetrics(wRC+·ERA-·WAR)로 받아 하루 한 번 덮어쓴다.
-- stat_group: HITTING / PITCHING, innings_outs: 이닝을 아웃 수로(50.2이닝 = 152), 지표는 받지 못했으면 null
-- 새 표라 블루-그린 배포 중 옛 API와 겹쳐도 안전하다.
create table player_season_metric (
    id                bigint      not null auto_increment primary key,
    player_id         bigint      not null,
    season            int         not null,
    stat_group        varchar(10) not null,
    plate_appearances int,
    innings_outs      int,
    games_started     int,
    wrc_plus          double,
    era_minus         double,
    war               double,
    fetched_at        datetime(6) not null,
    constraint uk_player_season_metric unique (player_id, season, stat_group),
    constraint fk_player_season_metric_player foreign key (player_id) references player (id)
);
