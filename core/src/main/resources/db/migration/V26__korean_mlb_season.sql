-- 한국인 메이저리거 역대 기록(기록 탭, 이슈 #177). 선수(MLB 번호)·시즌·묶음마다 한 줄. 새 표 하나라 블루-그린 배포 중
-- 옛 API와 겹쳐도 안전하다. 지금 명단에 없는 선수(은퇴·국내 복귀)도 있어 player 표가 아니라 MLB 번호로 잇는다.
-- MLB yearByYear를 받아 덮어쓴다(명단 밖 선수는 처음 한 번, 명단 선수는 하루 한 번). 여러 팀 시즌은 합친 줄 하나다.
-- stat_group: HITTING(games·hits·home_runs·rbi·stolen_bases) / PITCHING(games·wins·saves·strikeouts). 모르는 값은 null
create table korean_mlb_season (
    id           bigint      not null auto_increment primary key,
    mlb_id       bigint      not null,
    season       int         not null,
    stat_group   varchar(10) not null,
    games        int,
    hits         int,
    home_runs    int,
    rbi          int,
    stolen_bases int,
    wins         int,
    saves        int,
    strikeouts   int,
    fetched_at   datetime(6) not null,
    constraint uk_korean_mlb_season unique (mlb_id, season, stat_group)
);
