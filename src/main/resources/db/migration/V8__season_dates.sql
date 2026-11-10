-- MLB 시즌 날짜(이슈 #31). 수집기가 하루 한 번 올해·내년 것을 MLB(seasons/{year}?sportId=1)에서 받아 덮어쓴다.
-- 날짜는 모두 미국 날짜다. 지금 시즌은 올해 정규시즌 개막일(regular_season_start)이 지났으면 올해, 아니면 작년이다(CurrentSeason).
-- post_season_end: 포스트시즌 마지막 날(MLB가 주지 않으면 null), fetched_at: 마지막으로 받은 시각(UTC)
create table season_dates (
    season               int         not null primary key,
    regular_season_start date        not null,
    regular_season_end   date        not null,
    post_season_end      date,
    fetched_at           datetime(6) not null
);
