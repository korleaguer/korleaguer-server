-- balldontlie 선수 필드. 선수 매칭 때 채우고, 값이 없으면 null 이다.
-- 키·몸무게·투타는 받은 원문 그대로 저장하고(예: 6' 2", 194 lbs, Left/Right) 응답에서 바꾼다.
alter table player add column birth_date date;
alter table player add column height varchar(20);
alter table player add column weight varchar(20);
alter table player add column bats_throws varchar(20);
alter table player add column jersey varchar(10);
alter table player add column debut_year int;
alter table player add column age int;
