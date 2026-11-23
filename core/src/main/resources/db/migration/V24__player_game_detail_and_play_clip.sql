-- 선수 페이지 경기 달력에서 날짜를 누르면 뜨는 상세(이슈 #157).
-- player_game_detail: 한 선수의 한 경기 상세(타석별 기록·그 선수 하이라이트 영상)를 우리 응답 모양 JSON으로 둔다.
--   처음 열 때 MLB(playByPlay·content)에서 받아 만든다. complete가 true면(끝난 경기, 시작 하루 뒤 받음) 다시 받지 않고,
--   아니면(진행 중이거나 하이라이트가 아직 덜 올라왔을 수 있음) fetched_at으로부터 잠깐만 다시 쓴다(GameDetailService).
--   payload는 몇 KB다(타석 4~5개, 영상 주소 몇 개). varchar 최대에 맞춰 넘으면 영상을 덜어 넣는다.
-- play_clip: 공 하나 영상 주소(Baseball Savant). play_id는 MLB playByPlay의 공 번호(UUID).
--   mp4_url이 null이면 아직 영상이 없었다는 뜻이고, 시간이 지나면 다시 묻는다.
-- 새 표라 블루-그린 배포 중 옛 API와 겹쳐도 안전하다(옛 API는 이 표를 모른다).
create table player_game_detail (
    id         bigint         not null auto_increment primary key,
    player_id  bigint         not null,
    game_pk    bigint         not null,
    payload    varchar(15000) not null,
    complete   boolean        not null,
    fetched_at datetime(6)    not null,
    constraint uk_player_game_detail unique (player_id, game_pk),
    constraint fk_player_game_detail_player foreign key (player_id) references player (id)
);

create table play_clip (
    play_id    varchar(40)  not null primary key,
    mp4_url    varchar(500) null,
    fetched_at datetime(6)  not null
);
