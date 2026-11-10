-- MLB 공식 Stats API에서 받는 선수의 지금 단계와 이동 기록.
-- level: MLB / MINORS. 소속이 없거나(FA 등) 단계를 모르면 null 이다.
-- level_team_name: MLB가 알려 준 지금 소속 팀 이름(예: St. Paul Saints), level_checked_at: 마지막으로 확인한 시각(UTC)
alter table player add column level varchar(10);
alter table player add column level_team_name varchar(100);
alter table player add column level_checked_at timestamp with time zone;

-- 이동 기록(콜업·강등·부상자 명단 등). MLB 이동 기록 번호를 그대로 기본키로 쓴다.
-- 받은 줄은 모두 저장하고, 화면에 보일 줄은 응답을 만들 때 고른다. from_*는 비어 있을 때가 많다.
create table player_move (
    mlb_transaction_id bigint primary key,
    player_id          bigint      not null references player (id),
    move_date          date        not null,
    type_code          varchar(10),
    type_desc          varchar(100),
    description        varchar(1000),
    from_team_id       bigint,
    from_team_name     varchar(100),
    from_level         varchar(10),
    to_team_id         bigint,
    to_team_name       varchar(100),
    to_level           varchar(10)
);

create index idx_player_move_player on player_move (player_id, move_date);
