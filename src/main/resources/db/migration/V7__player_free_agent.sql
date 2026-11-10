-- MLB 이동 기록상 FA인 선수(이슈 #23). 마지막 'FA 선언' 뒤에 새 팀 기록이 없으면 true.
-- MLB '지금 소속'은 FA를 선언해도 바로 비지 않아서 이동 기록으로 정한다. 선수 정보를 받을 때마다 다시 정한다.
alter table player add column free_agent boolean not null default false;
