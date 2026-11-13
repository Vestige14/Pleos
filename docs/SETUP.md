# 개발 환경 설정과 실행

이 문서는 새 개발자·에이전트가 clone한 **현재 소스**를 준비하고 실행하기 위한 안내다. [AGENTS.md](../AGENTS.md), [인수인계](HANDOFF.md)와 `docs/`의 MD·PDF를 먼저 읽는다. 옛 실험용 명령과 현재 제품 명령을 섞지 않는다.

## 1. 저장소와 현재 상태 확인

```sh
git clone https://github.com/chowonbhin/Pleos.git
cd Pleos
git status --short
git branch -a
git log -5 --oneline
```

이미 clone한 저장소라면 중복 clone하지 말고 해당 폴더에서 확인한다. 로컬 변경과 다른 개발자의 작업을 보존한다. 인수인계에 등장하는 로컬 feature가 원격에도 있다고 가정하지 않는다. 없는 구현을 임의로 새로 만들지 말고 현재 개발자에게 인계 상태를 확인한다.

## 2. 필요한 환경 확인

| 항목 | 프로젝트 기준 |
| --- | --- |
| JDK | 17 |
| Android SDK | Platform 35, Build Tools 35.0.0, Platform Tools |
| Gradle | 저장소의 wrapper 사용. 현재 기준 8.13 |
| Android Gradle Plugin / Kotlin | 현재 기준 8.13.2 / 2.2.21. 실제 Gradle 파일과 대조 |
| 앱 | `app.memoryvault`, minSdk 29, targetSdk 34 |
| 실제 기능 시험 | 호스트 CPU에 맞는 공식 Pleos API34 이미지·전용 AVD, cold boot, RAM 4GB |

에이전트는 OS·CPU와 설치된 Java/SDK/AVD를 먼저 확인한다. 이미 맞는 버전이 있으면 재사용하고 다른 프로젝트의 설정을 전역으로 바꾸지 않는다. Android Studio 또는 Android command-line tools의 공식 경로로 누락된 구성 요소를 준비한다. 호스트에 맞지 않는 시스템 이미지를 그대로 설치하지 않는다.

Pleos 이미지와 AVD 설치 방법은 [공식 시작 안내](https://document.pleos.ai/docs/pleos-only/getting-started) 및 [프로젝트 환경 기록](environment/pleos-memory-vault-v4.md)을 따른다. 기존 기록의 이미지·도구 버전과 현재 공식 배포가 다르면 호환성을 확인하고 차이를 기록한다. 기록에 나온 특정 Mac 경로나 기기 이름이 모든 환경에 존재한다고 가정하지 않는다.

계정 로그인·라이선스 동의·OS 마이크 허용처럼 사용자 조작이 필요한 경우에는 해당 단계만 명확히 요청한다. 자동 설치가 가능하다고 해서 유료 서비스 가입·계정 생성·접근 권한 확대까지 승인된 것은 아니다.

## 3. 프로젝트 로컬 설정과 빌드

다음은 **macOS에서 JDK와 SDK가 준비된 경우**의 예시다. 다른 OS는 실제 설치 위치에 맞게 환경변수를 설정한다.

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_USER_HOME="$PWD/.local/android"
export GRADLE_USER_HOME="$PWD/.local/gradle-home"
mkdir -p "$ANDROID_USER_HOME" "$GRADLE_USER_HOME"
# 기존 local.properties가 있으면 내용을 확인하고 보존한다.
test -f local.properties || printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
./gradlew --no-daemon --console=plain assembleDebug
```

`JAVA_HOME`, `ANDROID_HOME`, 기존 `local.properties`의 SDK 경로가 유효한지 빌드 전에 확인한다. 첫 빌드에는 Gradle과 공개 의존성 다운로드가 필요할 수 있다. SDK 자격증명이나 다른 개발자의 debug 서명키를 복사해서 해결하지 않는다.

APK 경로: `app/build/outputs/apk/debug/app-debug.apk`.

3단계 ON_DEVICE STT 경로는 OpenAI 키가 필요한 경로가 아니다. OpenAI 백엔드는 후속 단계이며, 키를 앱·Git·문서에 넣지 않는다. `.local/`, `local.properties`, AVD 데이터, 서명키와 빌드 출력은 각 환경에서 준비한다.

## 4. 전용 기기 준비와 실행

에이전트는 설치된 AVD와 사용 중인 ADB·emulator 포트를 조사해 충돌 없는 전용 구성을 선택한다. 기존 AVD의 사용자 데이터·스냅샷을 복사하거나 다른 프로젝트 기기를 재설정하지 않는다. 생성 방법은 [환경 기록의 AVD 절차](environment/pleos-memory-vault-v4.md)를 참고한다.

아래 변수에는 **실제로 준비한 전용 AVD 이름과 사용하지 않는 포트**를 넣는다. ADB 서버 포트와 emulator의 짝수 포트·다음 포트가 다른 작업에 사용 중이지 않은지 확인한다.

```sh
export ANDROID_EMULATOR_HOME="$PWD/.local/android"
export ANDROID_AVD_HOME="$PWD/.local/avd"
: "${MV_AVD_NAME:?준비한 전용 AVD 이름을 지정하세요}"
: "${MV_ADB_PORT:?사용하지 않는 ADB 서버 포트를 지정하세요}"
: "${MV_EMULATOR_PORT:?사용하지 않는 emulator 짝수 포트를 지정하세요}"
export ADB_SERVER_SOCKET="tcp:localhost:$MV_ADB_PORT"
"$ANDROID_HOME/emulator/emulator" -avd "$MV_AVD_NAME" \
  -port "$MV_EMULATOR_PORT" -memory 4096 -no-snapshot -no-boot-anim -no-metrics
```

이미 해당 기기가 실행 중이면 중복 실행하지 않는다. emulator 실행 터미널을 유지하고, 같은 환경변수를 가진 별도 터미널에서 `adb devices -l`과 AVD 이름을 확인해 실제 serial을 `MV_SERIAL`로 지정한다. 연결이 보이지 않으면 선택한 ADB 서버와 해당 기기의 연결을 확인한다. 기기 이름이나 user 번호를 과거 기록에서 그대로 복사하지 않는다.

```sh
"$ANDROID_HOME/platform-tools/adb" devices -l
: "${MV_SERIAL:?확인한 전용 기기의 실제 serial을 지정하세요}"
"$ANDROID_HOME/platform-tools/adb" -s "$MV_SERIAL" emu avd name
"$ANDROID_HOME/platform-tools/adb" -s "$MV_SERIAL" shell getprop sys.boot_completed
MV_USER_ID="$("$ANDROID_HOME/platform-tools/adb" -s "$MV_SERIAL" shell am get-current-user | tr -d '\r')"
"$ANDROID_HOME/platform-tools/adb" -s "$MV_SERIAL" shell pm path --user "$MV_USER_ID" app.memoryvault
```

부팅 완료 값이 `1`이고 대상 AVD 이름·사용자 번호가 맞는지 확인한다. 패키지가 이미 존재하면 아래 신규 설치를 바로 실행하지 말고 기존 데이터·서명·인계 상태부터 확인한다. 서명 충돌을 해결하려고 기존 앱을 삭제하거나 무조건 `-r`로 덮어쓰지 않는다.

기존 앱이 없는 전용 기기임을 확인한 뒤:

```sh
"$ANDROID_HOME/platform-tools/adb" -s "$MV_SERIAL" install --user "$MV_USER_ID" app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/platform-tools/adb" -s "$MV_SERIAL" shell am start -W --user "$MV_USER_ID" -n app.memoryvault/.MainActivity
```

실행 화면이 현재 소스와 맞는지 확인한다. 초기 안내 앱을 실행한 것과 메모 기능을 검증한 것을 구분한다. 실제 음성 시험은 사용자 준비를 확인한 뒤 마이크를 사용하고 끝나면 시험 전 설정으로 복원한다. OS 주행 제한을 꺼서 제품 동작 검증을 통과시켰다고 주장하지 않는다. 최초 차량 권한과 안전 상태 조건은 [인수인계의 안전 계약](HANDOFF.md)을 따른다.

## 5. 확인 결과와 문제 해결

에이전트는 사용한 브랜치·커밋, JDK/SDK, 기기 이름·serial, 빌드 성공 여부, APK 위치, 실제로 확인한 화면과 미검증 기능을 보고한다. 실패하면 원인·막힌 단계·다음 조치를 구분한다. 원문 발화나 인증정보가 들어갈 수 있는 광범위 시스템 로그를 수집하지 않는다.

| 상황 | 우선 확인할 사항 |
| --- | --- |
| JDK·SDK 경로 오류 | 실제 설치 위치, JAVA_HOME, ANDROID_HOME, local.properties |
| 의존성 다운로드 실패 | 네트워크와 Gradle 저장소 설정. 임의 SDK 교체나 키 삽입으로 우회하지 않음 |
| 기기가 보이지 않음 | ADB 서버·전용 포트·serial·부팅 상태 |
| 설치 서명 충돌 | 기존 앱 보존, 새 전용 기기 사용 여부 |
| 차량 상태가 UNKNOWN | 연결 준비·권한·실제 차량 신호. 주차로 가정해 잠금 해제하지 않음 |
| 음성·알림이 시작되지 않음 | 현재 소스의 기능 유무, 실제 주행 상태, 마이크·알림 권한과 알림 채널 |

환경 설정 확인만을 위해 전체 제품 검증을 반복하지 않는다. 중간 코드 변경에는 관련 검사, 단계 완료에는 전체 필수 검증을 적용한다. 상세 기기 시험과 제한은 [플랫폼 운영 기록](environment/mv-02-platform-spikes.md), 단계 완료 조건은 [인수인계](HANDOFF.md)와 원본 설계를 따른다.

## 6. 게시 전

최신 main pull → 로컬 merge → 병합 결과 로컬 검증 → 인수인계 갱신·최종 변경 확인 → 비밀정보 점검 → 승인된 origin push 순서다. 코드·문서 어느 쪽을 올려도 적용한다. 원격이 바뀌면 관련 통합·검증을 갱신한다.

이 문서 정리는 로컬에 보관한다. 사용자 결정에 따라 **3단계 완료 후 상태·실행 방법을 최종 결과와 대조해 함께 업로드**하며, 3단계 완료 전 단독 게시하지 않는다.
