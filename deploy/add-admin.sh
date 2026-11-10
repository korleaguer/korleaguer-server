#!/usr/bin/env bash
# 서버에 관리용 계정을 만든다: SSH 키 로그인만, 비밀번호 없는 sudo.
# Claude Code가 서버 설정 바꾸기·장애 확인을 사람에게 비밀번호를 묻지 않고 직접 할 때 쓴다(2026-11-10 사용자 결정).
# 이 계정은 서버의 root 권한을 갖는다. 필요 없어지면 --remove로 지운다.
# 이 스크립트가 만든 계정만 다룬다(계정 설명에 'korleaguer-admin'을 남긴다). 사람·시스템 계정을 잘못 주면 거절한다.
#
# 사용(서버에서 root로, 다루려는 관리용 계정이 아닌 다른 계정에서):
#   sudo bash add-admin.sh --user claude --key-file korleaguer-admin.pub   # 만들기(다시 실행하면 키만 바뀐다)
#   sudo bash add-admin.sh --user claude --remove                          # 지우기(계정·홈·sudo 권한)
set -euo pipefail

MARK="korleaguer-admin"
ADMIN_USER=""
KEY_FILE=""
REMOVE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --user) ADMIN_USER="${2:-}"; shift 2 ;;
    --key-file) KEY_FILE="${2:-}"; shift 2 ;;
    --remove) REMOVE=1; shift ;;
    *) echo "모르는 옵션: $1" >&2; exit 1 ;;
  esac
done

[ "$(id -u)" = 0 ] || { echo "root(sudo)로 실행하세요" >&2; exit 1; }
[[ "$ADMIN_USER" =~ ^[a-z][a-z0-9_-]{1,31}$ ]] || { echo "--user에 소문자 계정 이름을 주세요(예: claude)" >&2; exit 1; }
case "$ADMIN_USER" in
  root | ubuntu | deploy) echo "기존 계정(${ADMIN_USER})은 쓰지 않습니다. 관리용 계정 이름을 따로 주세요" >&2; exit 1 ;;
esac
# sudo를 실행한 계정 자신은 다루지 않는다(자기 키를 덮어쓰거나, 접속한 채로 자기를 지우지 않게).
if [ "$ADMIN_USER" = "${SUDO_USER:-}" ]; then
  echo "지금 이 스크립트를 실행한 계정(${ADMIN_USER})은 다룰 수 없습니다. 다른 관리 계정(예: ubuntu)에서 실행하세요" >&2
  exit 1
fi
sudoers="/etc/sudoers.d/90-korleaguer-admin-${ADMIN_USER}"

# 이미 있는 계정이면 이 스크립트가 만든 것인지 본다: 계정 설명이 MARK이거나, 이 스크립트의 sudo 파일이 있다
# (설명을 남기기 전에 만든 계정). 아니면 사람·시스템 계정일 수 있으니 키를 덮어쓰거나 지우지 않는다.
exists=0
if entry="$(getent passwd "$ADMIN_USER")"; then
  exists=1
  uid="$(printf '%s' "$entry" | cut -d: -f3)"
  gecos="$(printf '%s' "$entry" | cut -d: -f5)"
  if [ "$uid" -lt 1000 ]; then
    echo "${ADMIN_USER}는 시스템 계정(UID ${uid})이라 다루지 않습니다" >&2
    exit 1
  fi
  if [ "$gecos" != "$MARK" ] && [ ! -e "$sudoers" ]; then
    echo "${ADMIN_USER}는 이 스크립트가 만든 계정이 아니라서 다루지 않습니다(계정 설명: '${gecos}')" >&2
    exit 1
  fi
fi

if [ "$REMOVE" = 1 ]; then
  if [ "$exists" = 1 ]; then
    home="$(getent passwd "$ADMIN_USER" | cut -d: -f6)"
    # 새 접속부터 막는다(키 지우기, 계정 만료). 설명에 MARK를 남겨 두어, 중간에 실패해도 다시 실행하면 마저 지운다.
    rm -f "$home/.ssh/authorized_keys"
    usermod --expiredate 1 --comment "$MARK" "$ADMIN_USER"
    rm -f "$sudoers"
    # 돌던 것을 끝낸다. 로그인 세션을 통째로 끝내야 그 계정이 sudo로 띄운 root 프로세스(sudo -i 등)까지 끝난다.
    # pkill은 유효 UID(-u)와 실제 UID(-U) 둘 다로 한 번 더 끝낸다.
    loginctl terminate-user "$ADMIN_USER" 2> /dev/null || true
    pkill -KILL -u "$ADMIN_USER" 2> /dev/null || true
    pkill -KILL -U "$ADMIN_USER" 2> /dev/null || true
    sleep 1
    if ! userdel -r "$ADMIN_USER"; then
      if getent passwd "$ADMIN_USER" > /dev/null; then
        echo "계정을 지우지 못했습니다. 키와 sudo 권한은 지웠고 계정은 만료시켜 접속할 수 없습니다. 잠시 뒤 다시 실행하세요" >&2
      else
        echo "계정은 지웠지만 홈 폴더(${home})를 다 지우지 못했습니다. 확인하고 직접 지우세요" >&2
      fi
      exit 1
    fi
  else
    rm -f "$sudoers"
    if [ -e "/home/${ADMIN_USER}" ]; then
      echo "참고: 계정은 없지만 /home/${ADMIN_USER}가 남아 있습니다. 확인하고 직접 지우세요" >&2
    fi
  fi
  echo "관리용 계정 ${ADMIN_USER}를 지웠습니다"
  exit 0
fi

[ -s "$KEY_FILE" ] || { echo "--key-file에 SSH 공개키 파일을 주세요" >&2; exit 1; }
key="$(head -n 1 "$KEY_FILE")"
[[ "$key" =~ ^ssh-(ed25519|rsa)\ [A-Za-z0-9+/=]+(\ [^[:cntrl:]]*)?$ ]] || { echo "SSH 공개키 형식이 아닙니다" >&2; exit 1; }

# 1) 계정. 비밀번호로는 로그인할 수 없게 '*'로 둔다('!' 잠금은 일부 SSH 설정에서 키 로그인도 막는다).
#    설명에 MARK를 남기고, 지우다 만 계정(만료됨)이면 만료를 푼다.
if [ "$exists" = 0 ]; then
  useradd --create-home --shell /bin/bash --comment "$MARK" "$ADMIN_USER"
fi
usermod -p '*' --comment "$MARK" --expiredate '' "$ADMIN_USER"
home="$(getent passwd "$ADMIN_USER" | cut -d: -f6)"

# 2) 이 키 하나만 받는다(다시 실행하면 바꾼다).
install -d -m 700 -o "$ADMIN_USER" -g "$ADMIN_USER" "$home/.ssh"
printf '%s\n' "$key" > "$home/.ssh/authorized_keys"
chown "$ADMIN_USER:$ADMIN_USER" "$home/.ssh/authorized_keys"
chmod 600 "$home/.ssh/authorized_keys"

# 3) 비밀번호 없는 sudo(문법을 먼저 확인하고 넣는다. 틀린 sudoers는 sudo 전체를 막는다).
tmp="$(mktemp)"
printf '%s ALL=(ALL) NOPASSWD:ALL\n' "$ADMIN_USER" > "$tmp"
chmod 440 "$tmp"
visudo -cf "$tmp" > /dev/null
mv "$tmp" "$sudoers"

# 4) SSH가 이 계정을 막지 않는지(접속 허용 목록이 있으면 알려 준다).
allow="$(sshd -T 2> /dev/null | grep -iE '^(allowusers|allowgroups) ' || true)"
if [ -n "$allow" ]; then
  echo "주의: sshd에 접속 허용 목록이 있습니다. ${ADMIN_USER}가 들어 있어야 접속됩니다:" >&2
  echo "$allow" >&2
fi

echo "관리용 계정 ${ADMIN_USER} 준비 끝(키 로그인, 비밀번호 없는 sudo). 키 지문: $(ssh-keygen -lf "$home/.ssh/authorized_keys" | cut -d' ' -f2)"
