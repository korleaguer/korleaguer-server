-- 마이너리그(트리플A ~ 루키) 경기별 기록(이슈 #33). 선수 정보와 같이 하루 한 번 그 시즌 전체를 받아
-- 선수·시즌마다 통째로 바꾼다(IngestWriter.saveMinorGameLog). 같은 경기의 타격·투구는 한 줄에 둔다.
-- game_date: 공식 날짜(미국 날짜), level: 리그 단계 약어(AAA·AA·A+·A·ROK), team_name·opponent_name: 마이너 팀 이름(영어)
-- 타격·투구 칸은 appearance와 같고, holds·blown_saves는 마이너 투구 기록에만 있다.
create table minor_game_log (
    id                 bigint       not null auto_increment primary key,
    player_id          bigint       not null,
    season             int          not null,
    game_pk            bigint       not null,
    game_date          date         not null,
    level              varchar(10),
    team_name          varchar(100),
    opponent_name      varchar(100),
    home               boolean,
    win                boolean,
    -- 타격
    at_bats            int,
    runs               int,
    hits               int,
    doubles            int,
    triples            int,
    home_runs          int,
    rbi                int,
    walks              int,
    strikeouts         int,
    hit_by_pitch       int,
    stolen_bases       int,
    sac_flies          int,
    plate_appearances  int,
    total_bases        int,
    -- 투구
    pitching_outs      int,
    hits_allowed       int,
    runs_allowed       int,
    earned_runs        int,
    walks_allowed      int,
    pitcher_strikeouts int,
    home_runs_allowed  int,
    pitch_count        int,
    wins               int,
    losses             int,
    saves              int,
    holds              int,
    blown_saves        int,
    constraint uk_minor_game_log_player_game unique (player_id, season, game_pk),
    constraint fk_minor_game_log_player foreign key (player_id) references player (id)
);

create index ix_minor_game_log_player_season on minor_game_log (player_id, season, game_date);
