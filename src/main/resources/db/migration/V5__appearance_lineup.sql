-- 경기별 선발·교체(이슈 #8). 박스스코어의 타순·나온 투수 순서로 정한다.
-- lineup: STARTER(선발) / SUB(교체). 아직 모르면 null 이다.
alter table appearance add column lineup varchar(10);

-- 지난 경기의 선발·교체를 뒤에서 천천히 채울 때, 박스스코어를 한 번 받아 본 경기는 다시 받지 않는다.
alter table game add column lineups_checked boolean not null default false;
