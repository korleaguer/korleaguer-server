-- 행선지 구단 분류에서 모델이 꾸며 쓴 답('NYM +: 메츠 관심' 등)을 읽지 못해 실패로 센 기사(이슈 #123).
-- 답 읽기를 넓혔으니 아직 고르지 못한 기사는 시도 횟수를 0으로 돌려 다시 고르게 한다.
update news_article
set destination_attempts = 0
where destinations_at is null
  and destination_attempts > 0;
