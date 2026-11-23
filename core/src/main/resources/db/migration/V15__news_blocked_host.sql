-- 기사 페이지를 401·403·451로 막은 사이트(이슈 #91). 그때까지 그 사이트의 기사 페이지는 받지 않는다(피드는 계속).
-- 서버를 다시 띄워도 이어지게 DB에 둔다. 새 표라 블루-그린 배포 중 옛 API와 겹쳐도 안전하다.
create table news_blocked_host (
    host          varchar(255) not null primary key,
    blocked_until datetime(6)  not null
);
