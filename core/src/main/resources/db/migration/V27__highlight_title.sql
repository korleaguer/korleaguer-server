-- 하이라이트 영상 한국어 제목(이슈 #199). MLB 영어 제목을 AI(뉴스 요약과 같은 싼 모델)로 한 번 옮겨 두고 다시 쓴다.
-- 같은 영어 제목은 한 줄이다(여러 선수·경기에서 같은 제목이 나와도 한 번만 옮긴다).
-- prompt_version: 옮길 때 쓴 모델과 지시문 판(예: claude-haiku-5-5/t1). 지금 판과 다르면 다시 옮겨 덮어쓴다.
-- 경기 상세 JSON(player_game_detail.payload)에는 한국어 제목을 넣지 않고 응답을 줄 때 이 표에서 붙인다
--   (번역이 늦게 생겨도 저장된 상세가 옛 모양으로 굳지 않는다).
-- 새 표라 블루-그린 배포 중 옛 API와 겹쳐도 안전하다(옛 API는 이 표를 모른다).
create table highlight_title (
    id             bigint       not null auto_increment primary key,
    title_en       varchar(300) not null,
    title_ko       varchar(100) not null,
    prompt_version varchar(50)  not null,
    created_at     datetime(6)  not null,
    constraint uk_highlight_title_en unique (title_en)
);
