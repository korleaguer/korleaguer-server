# 선수 사진 편집본

원 안에 나란히(또는 넘겨) 보이는 사진끼리 배경·얼굴 크기를 맞춘 편집본을 만든다. 두 가지다.

- `cut`: 홈 MLB 도전 카드의 포스팅 선수·선배 사진(흰 바탕 JPEG, 아래 '포스팅 선수'·'포스팅 선배')
- `silo`: 선수 페이지 과거 소속팀(KBO) 사진(투명 바탕 PNG, 아래 '과거 소속팀')

## 포스팅 선수

홈 MLB 도전 카드 맨 위 포스팅 선수 사진(76px 원, `src/main/resources/photos/kbo-{slug}-{photo-season}.jpg`)을 만든다.
원본은 현 소속 KBO 구단의 공식 선수 등록 사진이다. KBO 사진 서버 판(`KBO_IMAGE/person/middle/{시즌}/{번호}.jpg`)은 94×118이라
3배 화면의 76px 원에 필요한 228px의 41%밖에 안 돼 흐리다. 그래서 같은 사진의 큰 판(네이버 스포츠 이미지 서버
`sports-phinf.pstatic.net/player/kbo/default/{번호}.png`, 210×262, 배경 없음)으로 `cut` 편집본을 만든다(아래 선배 사진과 같은 틀).

- 큰 판은 선수의 **가장 최근** 등록 사진이라, KBO 사진 서버의 그 시즌 사진과 같은 사진인지 얼굴을 맞춰 겹쳐 본다.
  큰 판은 더 바짝 잘려 있고 작은 판은 강하게 선명화돼 있어서, 같은 사진이어도 아래 차이가 생긴다(다른 사진이라는 근거가 아니다).
  - 작은 판 맨 아래 속옷 깃 끝의 표식이 큰 판에는 잘려서 없다. 큰 판의 속옷 깃이 둥글게 보이는 것도 V자 끝이 잘려서다.
  - 작은 판의 모자 로고가 두 색 테두리로 보인다. 큰 판을 94×118로 줄여 선명화하면 똑같이 생기는 처리 흔적이다.
- 같은 사진인지는 겹친 두 판에서 깃(속옷) 바깥 경계·귀·모자 윤곽이 1px 안팎으로 맞는지, 턱·볼의 작은 붉은 점이 같은 자리에 있는지로 본다.
  노시환은 큰 판과 KBO 2026 판의 깃 경계 차이가 0.8px(94px 폭 기준)로 같은 촬영본인 KBO 2025·2026 판끼리(0.6px)와 같았고,
  다른 시즌 촬영본(2024·2023)은 2.9px·7.9px 어긋났다. 턱 점 무늬 상관도 0.91(2025·2026 판끼리 0.93, 2024·2023 판 0.31·0.26)이었다.
- 사진 이름에 `photo-season`이 들어간다. 새 시즌 사진으로 바꾸면 이름(주소)도 바뀌어, 하루 동안 저장해 둔 옛 사진이 남지 않는다.
  편집본이 없으면 공식 사진을 켠 서버는 뜨지 않는다(PhotoSources).

```bash
cd tools/posting-photo
xcrun swiftc -O -o /tmp/posting-photo posting-photo.swift
curl -sS -A 'korleaguer-photo-tool' -o /tmp/original.png 'https://sports-phinf.pstatic.net/player/kbo/default/<kbo-id>.png'
/tmp/posting-photo cut /tmp/original.png ../../src/main/resources/photos/kbo-<slug>-<photo-season>.jpg
/tmp/posting-photo measure ../../src/main/resources/photos/kbo-<slug>-<photo-season>.jpg
```

- `posting.yml` current에 `photo-season`과 `photo-url`(큰 판 주소)을 적고, 같은 사진의 KBO 사진 서버 주소는 주석으로 남긴다.
- 만든 사진은 선배 편집본과 나란히 놓고 눈으로 확인한다: 흰 배경, 모자 위 여유, 얼굴 크기·높이가 같다.

| 파일 | 선수 | 원본 | 잰 값(얼굴 중심 x·y, 얼굴 높이) |
|---|---|---|---|
| `kbo-si-hwan-roh-2026.jpg` | 노시환 한화 2026 | 네이버 큰 판(KBO 사진 서버 2025·2026 판과 같은 촬영본, 2026-11-18 대조) | 0.498·0.570, 0.492 |

## 포스팅 선배

홈 MLB 도전 카드의 선배 동그라미 사진(`src/main/resources/photos/posting-{MLB 선수 번호}-{시즌}.jpg`)을 만든다.
MLB 공식 증명사진은 배경 회색이 사진마다 다르고 얼굴이 사진에 꽉 차 있어서, 바로 위 포스팅 선수(KBO 공식 사진, 흰 배경)와 나란히 두면 어색하다.
그래서 배경을 지워 흰 바탕(#FFFFFF)에 놓고, 얼굴 크기·위치를 노시환 KBO 2026 공식 사진과 같게 맞춘 256×256 정사각형으로 저장한다.
화면은 이 사진으로 원을 채운다(노시환 사진과 같은 표시).

- 배경 지우기와 얼굴 찾기는 macOS Vision(사람 전경 마스크·얼굴 사각형)을 쓴다. 그래서 맥에서만 돈다(서버는 만든 사진을 그대로 준다).
- 기준 값(얼굴 높이 49.25%, 얼굴 중심 위에서 57.47%)은 `posting-photo.swift` 맨 위에 있다. 노시환 KBO 사진 서버 2026 판(94×118)을
  화면처럼 위쪽 정사각형으로 잰 값이고, 노시환 편집본(위 '포스팅 선수')도 이 값으로 만든다. 기준을 바꾸면 `measure`로 다시 재고
  포스팅 선수·선배 편집본을 모두 다시 만든다.

### 새 선배를 넣을 때

`roster/posting.yml` history에 `mlb-id`·`photo-season`·`photo-url`(계약 구단 시절 MLB 공식 사진, 출처 기록)을 적었으면 편집본도 만든다.
편집본이 없으면 공식 사진을 켠 서버는 뜨지 않는다(PhotoSources).

```bash
cd tools/posting-photo
xcrun swiftc -O -o /tmp/posting-photo posting-photo.swift
curl -sS -A 'korleaguer-photo-tool' -o /tmp/original.jpg '<photo-url>'
/tmp/posting-photo cut /tmp/original.jpg ../../src/main/resources/photos/posting-<mlb-id>-<photo-season>.jpg
```

- 원본은 posting.yml의 `photo-url`이다. 인터넷 보관소 주소가 열리지 않으면 운영 서버에 남은 원본(`https://pigeon.ddns.net/photos/mlb-<mlb-id>-<photo-season>`, 2026-11-11까지 받아 둔 것)을 쓴다.
- 만든 사진은 눈으로 확인한다: 흰 배경, 모자 위와 턱 아래에 여유, 얼굴이 가운데.
- 사진의 권리는 MLB·구단에 있다. 배경만 지우고 크기·위치만 맞춘다(얼굴·유니폼은 고치지 않는다).

## 과거 소속팀

선수 페이지 머리 원에서 지금 MLB 사진과 넘겨 보는 과거 소속팀(KBO) 사진(`src/main/resources/photos/career-{KBO 선수 번호}-{시즌}.png`)을 만든다.
지금 MLB 사진은 배경 없는 사진(headshot/silo, 360×360)이라, KBO 사진도 같은 틀로 맞춘다: 투명 바탕 288×288(원 지름 96px의 3배),
얼굴 높이 51.1%·얼굴 중심 위에서 51.6%·가로 가운데(MLB silo 사진 5장을 잰 평균, `posting-photo.swift` 맨 위).
화면은 두 사진을 같은 방식(원 안에 조금 작게 아래에 붙임)으로 보인다.

- 원본은 그 시즌 KBO 공식 선수 등록 사진이다. KBO 사진 서버(`KBO_IMAGE/person/middle/{시즌}/{번호}.jpg`)는 94×118이라 휴대폰에서 흐리므로,
  같은 사진의 큰 판(네이버 스포츠 이미지 서버 `sports-phinf.pstatic.net/player/kbo/default/{번호}.png`, 210×262, 배경 없음)을 쓴다.
  단 그 주소는 선수의 **가장 최근** 등록 사진이라, KBO 사진 서버의 그 시즌 사진과 같은 사진인지 눈으로 맞춰 본다(유니폼 깃·속옷·얼굴,
  자르기·선명화 때문에 생기는 차이와 맞춰 보는 법은 위 '포스팅 선수').
  다르면(그 뒤 시즌에 다시 KBO에 등록된 선수) KBO 사진 서버의 그 시즌 사진을 쓴다. `silo`는 배경이 있으면 지운다.
- 작은 원본은 먼저 Lanczos로 키운 뒤 배경을 지운다(경계가 계단처럼 보이지 않게). 얼굴·유니폼은 고치지 않는다.

| 파일 | 선수 | 원본 |
|---|---|---|
| `career-67341-2023.png` | 이정후 키움 2023 | 네이버 큰 판(2023 사진과 같음) |
| `career-67304-2024.png` | 김혜성 키움 2024 | 네이버 큰 판(2024 사진과 같음) |
| `career-64300-2020.png` | 김하성 키움 2020 | 네이버 큰 판(2020 사진과 같음) |
| `career-65357-2025.png` | 송성문 키움 2025 | 네이버 큰 판(2025 사진과 같음. KBO 서버 2025 사진은 가로로 늘어나 있다) |
| `career-67119-2023.png` | 고우석 LG 2023 | KBO 사진 서버 2023(94×118). 네이버 큰 판은 2026 LG 사진이라 쓰지 않았다 |

### 새 과거 소속팀 사진을 넣을 때

`roster/baseball.yml`에 `kbo-id`와 `career-photos`를 적었으면 편집본도 만든다. 편집본이 없으면 서버가 뜨지 않는다(PhotoSources).

```bash
cd tools/posting-photo
xcrun swiftc -O -o /tmp/posting-photo posting-photo.swift
curl -sS -A 'korleaguer-photo-tool' -o /tmp/original.png 'https://sports-phinf.pstatic.net/player/kbo/default/<kbo-id>.png'
/tmp/posting-photo silo /tmp/original.png ../../src/main/resources/photos/career-<kbo-id>-<season>.png
```

- 만든 사진은 지금 MLB 사진과 나란히 놓고 눈으로 확인한다: 얼굴 크기·높이가 같고, 어깨가 아래 끝까지 이어진다.
