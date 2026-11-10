-- 코리거 DB 첫 모양(MySQL 8). 배포 전이라 예전 V1~V6을 지금 모양 그대로 하나로 합쳤다(이슈 #25).
-- 로컬 실행·테스트는 H2를 MySQL 모드로 써서 같은 SQL로 만든다.
-- 시각(datetime(6))은 모두 UTC로 저장하고 응답에서만 한국 시간으로 바꾼다.
-- 번호: 팀은 MLB 팀 번호, 경기는 gamePk, 이동 기록은 MLB 이동 기록 번호를 그대로 기본키로 쓴다.

create table team (
    mlb_team_id  bigint       not null primary key,
    abbreviation varchar(10),
    name_en      varchar(100)
);

-- level: MLB / MINORS(소속이 없거나 모르면 null), level_team_name: MLB가 알려 준 지금 팀 이름,
-- level_checked_at: 선수 정보를 마지막으로 확인한 시각
create table player (
    id               bigint       not null auto_increment primary key,
    name_ko          varchar(50)  not null,
    mlb_id           bigint,
    name_en          varchar(100),
    position         varchar(50),
    birth_place      varchar(100),
    active           boolean      not null default true,
    birth_date       date,
    height           varchar(20),
    weight           varchar(20),
    bats_throws      varchar(20),
    jersey           varchar(10),
    debut_year       int,
    age              int,
    level            varchar(10),
    level_team_name  varchar(100),
    level_checked_at datetime(6),
    constraint uk_player_name_ko unique (name_ko),
    constraint uk_player_mlb_id unique (mlb_id)
);

-- 선수의 소속. 선수마다 한 줄이다: 팀을 옮기면 같은 줄을 고쳐 쓰고, 소속이 없어지면(FA 등) end_date만 남긴다.
create table affiliation (
    id         bigint  not null auto_increment primary key,
    player_id  bigint  not null,
    team_id    bigint  not null,
    start_date date    not null,
    end_date   date,
    loan       boolean not null default false,
    constraint uk_affiliation_player unique (player_id),
    constraint fk_affiliation_player foreign key (player_id) references player (id),
    constraint fk_affiliation_team foreign key (team_id) references team (mlb_team_id)
);

-- status_state: scheduled·in_progress·final·postponed 등. final_at: 끝난 것을 처음 본 시각,
-- checked_at: 박스스코어를 마지막으로 받은 시각, settled: 시작 3일 뒤 더는 다시 받지 않음,
-- lineups_checked: 선발·교체를 박스스코어로 한 번 채워 봤음
create table game (
    game_pk         bigint      not null primary key,
    season          int         not null,
    season_type     varchar(20),
    start_time_utc  datetime(6) not null,
    home_team_id    bigint      not null,
    away_team_id    bigint      not null,
    home_score      int,
    away_score      int,
    status_state    varchar(30) not null,
    venue           varchar(100),
    final_at        datetime(6),
    checked_at      datetime(6),
    settled         boolean     not null default false,
    lineups_checked boolean     not null default false,
    constraint fk_game_home_team foreign key (home_team_id) references team (mlb_team_id),
    constraint fk_game_away_team foreign key (away_team_id) references team (mlb_team_id)
);

create index idx_game_start_time on game (start_time_utc);

-- 선수의 경기 한 번. lineup: STARTER(선발) / SUB(교체), 모르면 null
create table appearance (
    id                 bigint not null auto_increment primary key,
    player_id          bigint not null,
    game_id            bigint not null,
    team_id            bigint not null,
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
    lineup             varchar(10),
    constraint uk_appearance_player_game unique (player_id, game_id),
    constraint fk_appearance_player foreign key (player_id) references player (id),
    constraint fk_appearance_game foreign key (game_id) references game (game_pk),
    constraint fk_appearance_team foreign key (team_id) references team (mlb_team_id)
);

-- 이동 기록(콜업·강등·부상자 명단 등). 받은 줄은 모두 저장하고, 화면에 보일 줄은 응답을 만들 때 고른다.
create table player_move (
    mlb_transaction_id bigint      not null primary key,
    player_id          bigint      not null,
    move_date          date        not null,
    type_code          varchar(10),
    type_desc          varchar(100),
    description        varchar(1000),
    from_team_id       bigint,
    from_team_name     varchar(100),
    from_level         varchar(10),
    to_team_id         bigint,
    to_team_name       varchar(100),
    to_level           varchar(10),
    constraint fk_player_move_player foreign key (player_id) references player (id)
);

create index idx_player_move_player on player_move (player_id, move_date);

-- 선수의 한 시즌 경기 기록을 한 번 다 받았다는 표시
create table player_season (
    id         bigint      not null auto_increment primary key,
    player_id  bigint      not null,
    season     int         not null,
    fetched_at datetime(6) not null,
    constraint uk_player_season unique (player_id, season),
    constraint fk_player_season_player foreign key (player_id) references player (id)
);

-- 선수의 시즌 마이너리그(트리플A ~ 루키) 출전 경기 수
create table minor_season (
    id             bigint      not null auto_increment primary key,
    player_id      bigint      not null,
    season         int         not null,
    hitting_games  int         not null,
    pitching_games int         not null,
    fetched_at     datetime(6) not null,
    constraint uk_minor_season_player_season unique (player_id, season),
    constraint fk_minor_season_player foreign key (player_id) references player (id)
);

-- 수집 실행 기록(실행마다 한 줄)
create table ingest_log (
    id            bigint        not null auto_increment primary key,
    kind          varchar(30)   not null,
    started_at    datetime(6)   not null,
    finished_at   datetime(6),
    status        varchar(30)   not null,
    message       varchar(1000),
    requests_used int
);
