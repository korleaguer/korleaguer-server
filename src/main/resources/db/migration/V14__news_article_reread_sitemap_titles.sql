-- 뉴스 사이트맵 출처 기사의 제목은 문장부호가 빠져 온다(엑스포츠뉴스 등, 이슈 #85). 기사 페이지를 읽을 때 og:title로 고치므로,
-- #82 배포 뒤 이미 페이지를 읽은 사이트맵 출처 기사를 한 번 더 읽게 한다(앞부분은 다시 채워지고 사진은 그대로 둔다).
update news_article
set page_read_at = null, lead_text = null
where page_read_at is not null
  and publisher in ('스포티비뉴스', '엑스포츠뉴스', '일간스포츠', '스포츠경향', 'MK스포츠', '스포츠춘추');
