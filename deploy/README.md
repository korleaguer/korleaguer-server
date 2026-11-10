# 코리거 배포(서버: pigeon.ddns.net)

develop에 PR이 병합되면 GitHub Actions가 테스트하고 Docker 이미지를 만들어 GHCR(GitHub 이미지 저장소)에 올리고,
SSH로 서버에 접속해 새 이미지로 바꾼다(지속 배포, 사람이 '배포'를 누르지 않는다). 서버는 이 폴더의 `compose.yaml`로 4개 컨테이너를 띄운다.

```
인터넷 ─80/443→ caddy(HTTPS 인증서 자동) → web(Next.js) → api(Spring Boot) → mysql(MySQL 8.4)
                                                            api·mysql은 밖으로 포트를 열지 않는다
```

- **배포 시작:** 저장소마다 develop 병합(푸시). 문서·테스트만 바뀐 푸시는 건너뛴다. API는 `korleaguer-server`, 웹은 `korleaguer-web`에서 따로 나간다.
- **순서:** 테스트(CI) → 이미지(SBOM·출처 증명 포함) → 서버 교체 → (웹) 공개 주소 `/healthz`가 그 커밋을 주는지 확인 → main을 배포한 커밋에 맞춤.
- **배포 구분:** 버전 번호 없이 커밋 해시로 구분한다. 이미지 이름표는 `sha-1a2b3c4`(병합된 커밋의 앞 7자리)이고, 배포 기록은 저장소 Actions의 `deploy` 실행 목록이다.
- **끊김 없이 바꾸기:** 새 컨테이너를 옛 것과 겹쳐 띄우고 healthy가 되면 옛 것을 내린다. 새 것이 정상이 안 되면 지우고 옛 것을 그대로 둔다.
- **API 배포 전 DB 백업:** `backups/`에 `before-<이미지 이름표>` 덤프(예 `before-sha-1a2b3c4`). 매일 04:30에도 백업(최근 14개).
- **되돌리기(롤백):** Actions → deploy → Run workflow에 되돌릴 커밋(예 `1a2b3c4`)을 넣는다. 서버에 최근 3개 이미지가 남아 있고, 그보다 오래된 것은 GHCR에서 받는다.
- **보안:** 배포 전용 계정 `deploy`의 SSH 키는 저장소마다 따로이고, 키마다 "그 서비스 배포" 명령 하나만 실행된다(셸·포트 포워딩 불가). 서버 호스트 키를 고정해 다른 서버에는 접속하지 않는다. 서버가 GHCR에서 이미지를 받을 때는 그 배포 job의 임시 토큰만 쓰고 지운다.

## 처음 한 번: 준비

비밀값(SSH 개인키, 토큰, 비밀번호)은 대화창이나 이슈에 붙여넣지 말고 아래 명령으로 직접 넣는다.

### 1. 내 컴퓨터에서 배포용 SSH 키 2개 만들기

```bash
ssh-keygen -t ed25519 -N "" -C gha-korleaguer-server -f ~/.ssh/korleaguer-deploy-server
ssh-keygen -t ed25519 -N "" -C gha-korleaguer-web -f ~/.ssh/korleaguer-deploy-web
```

### 2. 이 폴더와 공개키를 서버로 복사하고 준비 스크립트 실행

```bash
scp -r deploy ~/.ssh/korleaguer-deploy-server.pub ~/.ssh/korleaguer-deploy-web.pub 내계정@pigeon.ddns.net:~/korleaguer-setup/
ssh 내계정@pigeon.ddns.net
cd ~/korleaguer-setup/deploy
sudo ./server-setup.sh \
  --server-key "$(cat ../korleaguer-deploy-server.pub)" \
  --web-key "$(cat ../korleaguer-deploy-web.pub)"
```

- Debian·Ubuntu 계열에서 동작한다(Docker가 없으면 Docker 공식 저장소에서 설치).
- 마지막에 GitHub에 넣을 값(`DEPLOY_HOST`, `DEPLOY_PLATFORM`, `DEPLOY_KNOWN_HOSTS`)과 서버 키 지문을 출력한다.
- 비밀번호 로그인·root 로그인을 끄려면 `--harden-ssh`를 붙인다. **지금 쓰는 계정이 SSH 키로 로그인되는지 먼저 확인할 것**(아니면 서버에 못 들어가게 된다).
- 다시 실행해도 된다. 이미 있는 비밀 파일(`api.env`, `mysql.env`, `.env`)은 그대로 두고, 같은 서비스의 배포 키는 새 키로 바뀐다.

### 3. 공유기 포트

- HTTPS: 밖에서 80·443이 서버로 들어와야 Caddy가 인증서를 받는다(공유기 포트포워딩 80·443 → 서버).
  아직 안 되면 서버의 `/srv/korleaguer/.env`에서 `SITE_ADDRESS=:80`으로 두면 http로만 뜬다.
- SSH: GitHub Actions가 22번으로 들어온다(공유기 22 → 서버). 다른 포트면 `DEPLOY_PORT`를 넣는다.

### 3-1. 서버에 이미 nginx가 80·443을 쓰고 있을 때

Caddy는 서버 안쪽 주소에만 열고, 기존 nginx(인증서 포함)가 그쪽으로 넘기게 한다. `/srv/korleaguer/.env`:

```
SITE_ADDRESS=:80
HTTP_PORT=127.0.0.1:8088
HTTPS_PORT=127.0.0.1:8443
```

nginx 사이트 설정의 `location /`(예: `/etc/nginx/conf.d/korleaguer.conf`):

```nginx
location / {
    proxy_pass http://127.0.0.1:8088;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}
```

바꾼 뒤 `sudo nginx -t && sudo systemctl reload nginx`. HTTPS 인증서는 nginx(certbot)가 그대로 맡는다.
Caddyfile은 사설 대역(서버 안쪽)에서 온 nginx의 `X-Forwarded-For`·`X-Forwarded-Proto`를 믿도록 되어 있어 실제 접속자 IP와 https 여부가 web·api까지 전달된다.

로그 상한(`logging`)은 컨테이너를 새로 만들 때 적용된다. web·api는 배포 때마다 새로 만들어지고, caddy·mysql은 `docker compose up -d`로 바뀐 설정이 적용된다.

### 4. GitHub에 값 넣기(두 저장소 각각)

```bash
# 개인키는 저장소마다 자기 것만
gh secret set DEPLOY_SSH_KEY -R korleaguer/korleaguer-server < ~/.ssh/korleaguer-deploy-server
gh secret set DEPLOY_SSH_KEY -R korleaguer/korleaguer-web < ~/.ssh/korleaguer-deploy-web

# 서버 준비 스크립트가 출력한 값(두 저장소에 같게)
for repo in korleaguer/korleaguer-server korleaguer/korleaguer-web; do
  gh variable set DEPLOY_HOST -R $repo --body "pigeon.ddns.net"
  gh variable set DEPLOY_PLATFORM -R $repo --body "linux/amd64"   # 출력된 값(arm이면 linux/arm64)
  gh variable set DEPLOY_KNOWN_HOSTS -R $repo --body "pigeon.ddns.net ssh-ed25519 AAAA..."  # 출력된 한 줄 그대로
done
```

Free 조직의 private 저장소는 Environment·조직 secret을 쓸 수 없어서 저장소마다 넣는다.

## 배포하기

PR을 develop에 병합하면 끝이다. 워크플로가 그 커밋을 테스트해 이미지로 만들고 배포한다. 성공하면 main을 그 커밋에 맞춘다.
API와 웹이 함께 바뀌는 변경은 API PR을 먼저 병합한다(웹은 옛 API에도 깨지지 않게 만든다).
실패하면 Actions가 실패로 표시되고(메일 알림) 서버는 옛 것 그대로다. 고친 뒤 다시 병합하거나 실패한 실행을 '다시 실행'한다.

- 진행 상황: 저장소 Actions 탭의 `deploy`. 서버에도 `/srv/korleaguer/deploy.log`에 남는다(SSH가 중간에 끊겨도 배포는 끝까지 간다)
- 서버에서 보기: 아래처럼 deploy 계정으로 들어가서 본다(`/srv/korleaguer`는 deploy 계정만 열 수 있다)

```bash
sudo -iu deploy
cd /srv/korleaguer
docker compose ps
docker compose logs -f api
```

## 되돌리기·직접 배포

- GitHub: Actions → deploy → Run workflow → `commit`에 되돌릴 커밋(예 `1a2b3c4`, 그 저장소의 develop 커밋)
- 서버에서 직접(이미지가 서버에 있을 때): `sudo -iu deploy` 뒤 `KORLEAGUER_SKIP_PULL=1 korleaguer-deploy api sha-1a2b3c4`
- DB 구조가 바뀐 배포를 되돌릴 때는 `backups/`의 `before-sha-…` 덤프로 함께 복원한다.

## 서버 스크립트를 바꿨을 때

`korleaguer-deploy`·`korleaguer-backup`을 고쳤으면 서버에 다시 설치한다. 배포 워크플로는 이 파일을 바꾸지 못한다
(배포 키는 배포 명령 하나만 실행할 수 있게 묶여 있다). 내 컴퓨터의 이 저장소 폴더에서:

```bash
scp deploy/korleaguer-deploy 내계정@pigeon.ddns.net:/tmp/korleaguer-deploy && ssh -t 내계정@pigeon.ddns.net \
  'sudo install -m 755 /tmp/korleaguer-deploy /usr/local/bin/korleaguer-deploy && rm /tmp/korleaguer-deploy && echo 설치 완료'
```

`compose.yaml`·`Caddyfile`을 고쳤으면 `server-setup.sh`를 다시 실행한다(위 "처음 한 번"의 2번).

## 관리용 계정(Claude Code)

서버 설정 바꾸기·장애 확인을 Claude Code가 사람에게 비밀번호를 묻지 않고 직접 하도록 관리용 계정 `claude`를 둔다(2026-11-10 결정).
SSH 키로만 로그인되고 비밀번호 없는 sudo라, **서버의 root 권한을 갖는다.** 배포 계정(`deploy`)과는 따로다.

- 내 컴퓨터: 키 `~/.ssh/korleaguer-admin`, 접속 이름 `korleaguer-admin`(`~/.ssh/config`). 작업 폴더의 `.claude/settings.local.json`이
  `ssh korleaguer-admin …` 명령만 묻지 않고 실행되게 허용한다.
- 만들기·키 바꾸기(`add-admin.sh`, 다시 실행해도 된다). 내 컴퓨터의 이 저장소 폴더에서:

```bash
scp deploy/add-admin.sh ~/.ssh/korleaguer-admin.pub 내계정@pigeon.ddns.net:/tmp/ && ssh -t 내계정@pigeon.ddns.net \
  'cd /tmp && sudo bash add-admin.sh --user claude --key-file korleaguer-admin.pub && rm -f add-admin.sh korleaguer-admin.pub'
```

- 회수: 서버에서 `sudo bash add-admin.sh --user claude --remove`(계정·홈·sudo 권한을 지운다). 내 컴퓨터에서는 키 두 파일,
  `~/.ssh/config`의 `korleaguer-admin` 묶음, 작업 폴더 `.claude/settings.local.json`의 허용 규칙을 지운다.

## 백업·복원

```bash
sudo -iu deploy
cd /srv/korleaguer
korleaguer-backup manual                           # 지금 백업
gunzip -c backups/korleaguer-…-before-sha-1a2b3c4.sql.gz \
  | docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot "$MYSQL_DATABASE"'
```

백업은 서버 안에만 있으니 외장 디스크·클라우드로도 복사해 두는 것을 권한다.

## 관리용 API

API는 밖에 열려 있지 않다. 서버에 SSH로 들어가 컨테이너 안에서 부른다(토큰은 `api.env`의 `KORLEAGUER_ADMIN_TOKEN`).

```bash
sudo -iu deploy
cd /srv/korleaguer
docker compose exec api sh -c 'curl -s -X POST -H "X-Admin-Token: $KORLEAGUER_ADMIN_TOKEN" http://localhost:8080/admin/ingest/players'
```

## 알아 둘 것

- **DB 구조를 바꾸는 배포:** 새 API가 Flyway로 DB를 바꾸는 동안 옛 API도 잠깐 같이 돈다. 그래서 바뀐 구조가 옛 코드에서도 돌아가게 한다(열·표 추가는 먼저, 지우기는 다음 배포에서).
- **API가 겹쳐 뜨는 동안:** 수십 초 동안 두 API가 함께 떠 있어 수집이 한 번 겹칠 수 있다. 같은 경기·기록은 DB의 유일 제약이 막고, 실패한 수집은 다음 실행(12분 뒤)에 다시 한다.
- **서버 설정 파일이 바뀌면**(`compose.yaml`, `Caddyfile`, 스크립트): 이 폴더를 서버에 다시 복사하고 `server-setup.sh`를 다시 실행한 뒤,
  deploy 계정으로 `cd /srv/korleaguer && docker compose up -d caddy mysql && docker compose restart caddy`
  (Caddy는 시작할 때만 Caddyfile을 읽어서, 설정이 같으면 `up`만으로는 다시 읽지 않는다).
- **No-IP 무료 주소**는 30일마다 확인하지 않으면 만료된다.
- **GHCR:** 지금은 컨테이너 이미지 저장·전송이 무료다(바뀌면 1개월 전에 알려 준다). 오래된 이미지는 GitHub 조직의 Packages에서 지울 수 있다.
