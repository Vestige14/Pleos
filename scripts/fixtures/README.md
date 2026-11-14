# 로컬 AI 전송 수락 시험 fixture

현재 전체 기기 검사에 포함된 `MemoryLinkTransportAcceptanceTest`의 전용 서버다. [일반 시험 안내](../../docs/TESTING.md#3-roomcompose-기기-검사), [AI 기능 안내](../../docs/features/openai-sync.md)와 함께 사용한다. 새 환경에서도 저장소 안의 이 fixture를 사용하며 `/tmp`의 과거 작업 자료를 요구하지 않는다.

이 서버는 로컬 fake 모델만 사용한다. 실제 OpenAI 키는 필요 없다. 개발 세션 토큰은 앱과 이 로컬 서버를 인증하는 별도 비밀값이며 OpenAI 키를 대신 넣으면 안 된다. 제품의 `create_app`·인증·요청 schema·`LinkService`·멱등 처리를 재사용한다. 기존 `app.main`의 adapter 간접 import는 있지만 supplied fake service를 전달하므로 실 client 생성이나 `from_env` 분기는 실행하지 않는다.

## 전용 환경과 세션 준비

아래 명령은 모두 저장소 루트에서 실행한다. 사용 가능한 기존 Python 3.11 이상 backend venv는 재사용한다. 없는 새 환경에서만 [백엔드 준비 절차](../../backend/README.md#준비와-전체-검사)에 따라 `backend/.venv`와 고정 의존성을 준비한다. 기존 venv를 덮어쓰거나 개인 workspace의 절대 경로를 복사하지 않는다.

새 전용 환경에서는 개발자가 자기 비밀 관리 도구에 준비한 **32~256자의 base64url 토큰**(영문·숫자·`_`·`-`)을 사용한다. 저장소 루트의 `.local/verification/stage6-local-session.secret`는 자기 소유의 일반 파일·권한0600으로 준비하고 저장소나 보고서에 포함하지 않는다. fixture는 이 파일을 읽기만 하며 생성·교체·출력하지 않는다.

필요한 새 로컬 파일만 만드는 다음 예시는 대화형 터미널에서 토큰을 숨김 입력한다. 토큰 값은 명령 인수·셸 이력·출력에 들어가지 않는다. 같은 값을 다음 단계의 정상 앱 UI에 입력하므로 자기 비밀 관리 도구에서 유지한다. 기존 파일이 있으면 중단하며 기존 검증기의 세션을 재설정하지 않는다. 아래 준비는 새 전용 환경에서 개발자가 수행할 단계이며 문서를 읽는 것만으로 실행되지 않는다.

```sh
python3 -c '
import getpass, os, re, sys
from pathlib import Path
if not sys.stdin.isatty():
    raise SystemExit("숨김 입력이 가능한 대화형 터미널이 필요합니다")
folder = Path(".local/verification")
folder.mkdir(parents=True, exist_ok=True)
path = folder / "stage6-local-session.secret"
token = getpass.getpass("자기 로컬 개발 세션 토큰: ")
if not re.fullmatch(r"[A-Za-z0-9_-]{32,256}", token):
    raise SystemExit("개발 세션 토큰 형식을 확인하세요")
try:
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
except FileExistsError:
    raise SystemExit("기존 로컬 세션 파일을 보존합니다")
with os.fdopen(fd, "wb") as output:
    output.write(token.encode("ascii") + b"\n")
'
```

자기 전용 Pleos 기기에서 공식 P·0과 앱의 신뢰된 주차 상태를 확인한 뒤 **기억 저장소 → AI 방문 연결 → 연결 설정 열기**로 들어간다. 주소는 `http://127.0.0.1:18761`, 토큰은 위 파일과 같은 값을 입력해 저장한다. `http://localhost:18761`도 시험에서 허용한다. AndroidKeystore에 저장된 세션은 마스킹돼 보이며 시험은 그것을 읽기만 한다. 저장 토큰을 조회·출력하거나 OpenAI 키를 입력하지 않는다. 이미 준비된 검증 기기의 세션은 교체하지 않는다. 이 시험에는 재녹음이나 추가 마이크 권한이 필요 없다.

## 서버 실행과 연결

호스트 `127.0.0.1:18761`이 비어 있는지 먼저 확인한다. 점유 중이면 해당 프로세스 소유자와 조율하고 다른 프로세스를 kill하거나 포트를 바꾸지 않는다. 서버는 루프백18761에만 고정 bind하며 충돌·누락/안전하지 않은 세션 파일은 고정 오류로 거절한다.

```sh
backend/.venv/bin/python scripts/fixtures/memory_link_transport_server.py --help
backend/.venv/bin/python scripts/fixtures/memory_link_transport_server.py \
  --repo-root "$PWD" --max-seconds 300
```

두 번째 명령은 소유자가 관리하는 foreground 터미널에서 실행한다. 기본 수명은300초이며 허용 범위는60~600초다. 서버는 child 환경의 API·세션·모델·proxy·logging 변수를 비운 뒤 backend를 불러온다. access log는 끄고 고정 phase·request hash·HTTP status·call count만 출력한다. 본문·headers·토큰·좌표를 기록하지 않는다. 호스트의 `http://127.0.0.1:18761/`에 인증 없는 GET을 보내면404이며 모델 호출은 없다. 포트가 열렸다는 사실만으로 기기 연결이나 인증 성공을 판단하지 않는다.

다른 터미널에서 [SETUP](../../docs/SETUP.md)의 현재 `MV_ADB`, `ADB_SERVER_SOCKET`, `MV_SERIAL`, `MV_USER_ID`를 확인한다. serial과 사용자 번호는 자기 전용 기기의 실제 값이어야 한다. 기존 mapping을 기록하고 `tcp:18761`이 없을 때만 생성한다. 이미 있으면 소유자와 목적을 확인하며 다른 mapping을 덮어쓰지 않는다.

```sh
"$MV_ADB" -s "$MV_SERIAL" reverse --list
# 같은 전용 기기에 해당 mapping이 없다고 확인한 경우에만 실행
"$MV_ADB" -s "$MV_SERIAL" reverse --no-rebind tcp:18761 tcp:18761
```

제품·시험 APK의 소스/해시와 기존 설치의 서명을 확인하고 같은 서명으로 데이터 보존 설치한다. P·0, 호스트 마이크 입력off, 다른 시험/녹음 종료를 확인한다. 일반 전체 실행에도 이 서버·실제 저장 세션·reverse가 필요하다. 준비가 빠지면 새 transport 시험은 실패하며, 이를 제품 결함으로 단정하거나 skip으로 바꾸지 않는다.

## 정확한 후속 scoped 필터와 판독

다음은 기존 UI 원문 표시 보완과 신규 HTTP 수락을 확인했던2개 메서드의 필터다. 실행할 검증 경계에서 필요한 전체 범위를 대신한다는 뜻은 아니다. 일반 전체135개 명령과 실패 판독은 [TESTING 3절](../../docs/TESTING.md#3-roomcompose-기기-검사)을 따른다.

```sh
"$MV_ADB" -s "$MV_SERIAL" shell am instrument --user "$MV_USER_ID" -w -r \
  -e class app.memoryvault.ui.MemoryVaultScreenTest#pendingVoiceDisplaysExactReadOnlyText_andTypedSaveRequiresAnExplicitConfirmation,app.memoryvault.sync.MemoryLinkTransportAcceptanceTest#logicalOfflineSave_thenRealHttp503_reopenAndRetry_preservesOriginalAndSuggestsOnce \
  app.memoryvault.test/androidx.test.runner.AndroidJUnitRunner
```

신규 시험은 자기 UUID 파일 DB와 합성 기록만 사용한다. 연결 predicate=false에서 저장/WAITING/attempts0/HTTP0을 확인한 뒤 실제 `HttpURLConnection`과 저장된 세션으로 서버에 요청한다. 특수 합성 case만 인증/schema 검사 이후 첫503을 받고, 다음 동일 요청은 기존 `LinkService`와 로컬 fake 모델의 유효 SUGGEST를 받는다. 그 외 요청은 로컬 REVIEW/NO_MATCH만 가능하다. Android 쪽 성공 응답이나 connection을 fake로 대체하지 않는다.

시험은 실패 뒤 DB close/reopen, 불변 원문·job·snapshot·정책, 정확한 emoji codepoint span, 제안1개/DONE/attempts2와 최종 재열기 보존을 확인한다. **논리적 offline predicate와 주입 queue 시계**를 사용하므로 실제 OS 연결 차단/재접속이나 실제30초 경과 backoff 측정이 아니다. 기존 WorkManager 연결 제약 검사는 별도 근거다. 시험이 만든 UUID DB만 정리하고 기존 암호화 세션·제품DB·사진·원음을 보존한다.

2026-11-15 후속 회차는 이2개가 단1회 **2/2 PASS,2.181초**였다. 앞선 필터 없는134개는132PASS/2FAIL,72.685초였고 새135개 전체를 실행한 것은 아니다. `CaptureExternalPcmProbeTest`의 무음20초 timeout은 여전히 실패 기록이며 삭제·skip·assert 완화·timeout 확대로 감추지 않는다. SDK 진단과 제품 기능 수락을 구분하고, 향후 단계/릴리즈에서 선택한 전체 검증 범위와 실제 실패를 그대로 보고한다.

## 종료

foreground 서버를 정상 Ctrl+C/SIGTERM으로 종료하거나 설정한 수명 종료를 기다린다. `FAKE_MODEL_CLOSED`/`FIXTURE_STOPPED`와 자기 프로세스의 포트 종료를 확인한다. daemon·restart loop·scheduler는 없다. 자기 세션에서 생성했고 그대로 남아 있는 reverse만 확인 후 제거한다.

```sh
"$MV_ADB" -s "$MV_SERIAL" reverse --list
# 위에서 자신이 만든 동일 mapping인 경우에만 제거
"$MV_ADB" -s "$MV_SERIAL" reverse --remove tcp:18761
```

기존 세션 파일·기기 저장 세션·제품DB·음성·사진·다른 mapping이나 과거 근거를 삭제하지 않는다. 서버/기기 실행 권한과 실제 마이크 시험 시점은 담당 총괄이 조정한다.
