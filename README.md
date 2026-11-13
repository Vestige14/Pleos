# 기억 저장소 · Pleos

현재 브랜치는 **차량 상태에 따른 화면 잠금, 주행 중 명시적 버튼 녹음, 음성 원문의 Room 저장, 주차 후 하루 초안 표시**를 연결한 3단계 구현입니다. 주차 중 방문별 직접 메모를 추가·수정할 수 있고, 음성 원문은 방문 미지정 목록에 `연결 분석 대기`로 표시합니다. 앱 재실행 시 저장된 메모와 시드 방문을 다시 읽습니다. 실제 기기·음성·재실행을 포함한 **전체 3단계의 최종 수락 검증은 별도**이며, 최신 결과와 남은 조건은 [개발 상태](docs/development-status.md)에 기록합니다. 각 단계 이후 결과를 보고하고 중단하며, 다음 단계는 사용자 지시 후 진행합니다.

첫 릴리즈 목표는 **v0.1.0: F1~F10 + OpenAI 방문 연결·사용자 확인 + 통합 검증**입니다(2026-11-14 사용자 결정). 기존 3·5·7단계는 중간 개발 체크포인트이며, 8단계까지 전체 완료 기준을 충족했을 때 첫 릴리즈를 만듭니다. F11 전체 배지 확장·F12 사진 이동·F13 문장 다듬기·F14 링크 공유·F15 3D 기억 맵은 후속 범위입니다. 상세 수락 기준과 이전 버전 배정의 변경은 [첫 릴리즈 범위](docs/development-status.md#첫-릴리즈-범위--2026-11-14-사용자-결정)에 기록했습니다.

로컬 환경: JDK 17, Android SDK 35, AGP 8.13.2, Gradle 8.13, Kotlin 2.2.21. 앱은 `app.memoryvault`, minSdk 29, targetSdk 34입니다. 실제 차량 신호와 음성 인식은 호환되는 Pleos Playground AAOS 이미지와 SpeechToText 서비스가 필요합니다. SDK는 `ai.pleos.playground:SpeechToText:3.3.1`로 고정하고 `ON_DEVICE`만 사용합니다. API 키·SDK 등록·인터넷 권한 없이 빌드하며 녹음 파일이나 부분 인식문은 저장하지 않습니다.

## 빌드

새 clone의 프로젝트 루트에서 실행합니다. JDK 17과 Android SDK를 먼저 설치합니다. 아래는 macOS 예시이며, 다른 운영체제에서는 JAVA_HOME과 ANDROID_HOME을 자신의 설치 경로로 설정합니다.

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_USER_HOME="$PWD/.local/android"
export GRADLE_USER_HOME="$PWD/.local/gradle-home"
mkdir -p "$ANDROID_USER_HOME" "$GRADLE_USER_HOME"
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
./gradlew --no-daemon --console=plain assembleDebug assembleDebugAndroidTest lintDebug testDebugUnitTest
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. 최초 빌드에는 공개 의존성 다운로드가 필요합니다. 저장된 소스·Gradle wrapper·스키마·시드로 빌드하고, 제외된 캐시·인증 파일을 복사하지 않습니다. 실제 Room/Compose instrumentation은 실행 기기에서 별도로 검증하며, 검증 기록은 [개발 상태](docs/development-status.md)를 따릅니다.

SDK의 내부 로그 제거가 적용되도록 debug/release 모두 R8을 사용하며 debug 앱도 `debuggable=false`입니다. 실제 발화 전에는 **해당 APK**에 대해 다음 검사를 먼저 통과시킵니다.

```sh
python3 scripts/verify_vendor_log_dex.py app/build/outputs/apk/debug/app-debug.apk \
  --dexdump "$ANDROID_HOME/build-tools/35.0.0/dexdump"
```

## 새 AVD에서 확인

프로젝트 전용 AVD를 별도로 만듭니다. 기존 AVD의 사용자 데이터를 복제하지 않습니다. AVD 생성 과정은 [환경 기록](docs/environment/pleos-memory-vault-v4.md)에 있습니다. 아래 이름·포트가 이미 다른 기기에 사용 중이면 사용하지 않는 이름·포트를 정하고 모든 명령에서 일치시킵니다.

```sh
export ANDROID_EMULATOR_HOME="$PWD/.local/android"
export ANDROID_AVD_HOME="$PWD/.local/avd"
export ADB_SERVER_SOCKET=tcp:localhost:5038
"$ANDROID_HOME/emulator/emulator" -avd Pleos_MemoryVault_API34 \
  -port 5580 -memory 4096 -no-snapshot -no-boot-anim -no-metrics
```

에뮬레이터가 이미 실행 중이면 위 명령을 중복 실행하지 않습니다. 동일한 환경 변수를 설정한 다른 터미널에서 설치·실행합니다.

```sh
export MV_SERIAL=emulator-5580 # 앞에서 만든 새 AVD의 실제 serial
MV_USER_ID="$("$ANDROID_HOME/platform-tools/adb" -s "$MV_SERIAL" shell am get-current-user | tr -d '\r')"
"$ANDROID_HOME/platform-tools/adb" -s "$MV_SERIAL" shell pm path --user "$MV_USER_ID" app.memoryvault
# 위 결과가 비어 있고 새 AVD임을 확인한 경우에만 설치합니다.
"$ANDROID_HOME/platform-tools/adb" -s "$MV_SERIAL" install --user "$MV_USER_ID" app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/platform-tools/adb" -s "$MV_SERIAL" shell am start -W --user "$MV_USER_ID" -n app.memoryvault/.MainActivity
```

기존 패키지 경로가 나오면 설치를 중단하고 새 AVD를 선택합니다. 기존 앱을 덮어쓰거나 데이터를 지우지 않습니다.

첫 실행은 신뢰할 수 있는 차량 신호가 확인될 때까지 `차량 상태를 확인할 수 없습니다` 화면입니다. UNKNOWN에서는 메모·키보드·권한 요청을 열지 않습니다. 처음 설치한 앱에 차량 속도 권한이 없어 UNKNOWN인 경우, **공식 시뮬레이터에서 P·속도 0을 직접 확인한 뒤** 외부 시스템 설정에서 최초 권한을 허용합니다. 아래 명령은 테스트·개발용으로 설정 화면만 엽니다. 앱 자체는 UNKNOWN에서 설정 화면을 열지 않으며, 차량 상태를 P로 꾸미지 않습니다.

```sh
"$ANDROID_HOME/platform-tools/adb" -s "$MV_SERIAL" shell am start -W --user "$MV_USER_ID" \
  -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:app.memoryvault
```

시스템 설정의 앱 권한에서 차량 속도 권한을 허용하고 앱으로 돌아와 `차량 상태 다시 확인`을 누릅니다. 허용되지 않거나 신호가 불완전하면 계속 잠깁니다. 일반 폰 이미지나 지원되지 않는 이미지에서는 차량 신호를 확인할 수 없습니다.

확인 순서:

1. P가 확인되면 `하루 초안`, `시연 데이터 · 2026-11-08 (Asia/Seoul)`, 시간순 방문 세 곳이 표시됩니다. `메모 추가`로 직접 쓴 메모를 저장하고 `메모 수정`으로 바꿀 수 있습니다.
2. P에서 `음성 권한 설정`을 눌러 마이크·음성 인식·알림 권한을 명시적으로 허용하고 `시작 알림 켜기`를 선택합니다. 차단한 알림 채널이나 영구 거부 권한은 주차 후 시스템 설정에서 확인합니다.
3. 공식 시뮬레이터에서 주행 상태로 바꾸면 원문과 편집 화면이 숨겨집니다. 주행 녹음의 주경로는 준비된 알림의 `녹음 시작` 버튼입니다. 플랫폼이 Activity 표시를 허용하는 경우 제한 화면의 `한마디 남기기`도 같은 서비스로 연결됩니다. 앱은 OS의 Activity 설치·주행 제한을 우회하지 않습니다.
4. 준비/실제 녹음/처리/저장 상태는 서비스 상태에 따라 바뀌며, 알림이나 제한 화면에서 취소할 수 있습니다. 녹음은 최대 15초이고 첫 최종 인식문만 저장합니다. 내비게이션으로 이동해도 승인된 녹음은 계속되며, 실제 UNKNOWN이나 사용자 취소는 세션을 중단합니다.
5. 다시 P로 돌아오면 음성 원문이 `방문 미지정 음성 메모`에 `연결 분석 대기 · 음성 원문`으로 표시됩니다. 음성 원문은 수정할 수 없습니다. 앱을 종료·재실행해 같은 원문과 직접 메모가 남고 시드 방문이 중복되지 않는지 확인합니다.

화면의 **시연 녹음 시각은 2026-11-08 14:58 (Asia/Seoul)로 고정**되어 있습니다. 실제 오늘의 기록이나 자동 방문 연결을 의미하지 않습니다. 주행·UNKNOWN·전환 상태가 되면 편집 dialog와 IME를 닫고 작성 중인 내용을 숨깁니다. 미전송 내용은 저장된 메모로 자동 등록하지 않으며, 주차 후 `작성 중인 메모 이어 쓰기`로 이어 쓸 수 있습니다. 시스템이 저장한 Activity 상태 복원은 지원하지만 강제 종료·데이터 삭제 뒤 미전송 초안의 영구 복원은 보장하지 않습니다.

일반 실행·저장·Compose 검증에는 실제 마이크를 켤 필요가 없습니다. 실제 발화는 사용자 승인 후 최종 APK의 DEX 검사를 통과한 상태에서 진행합니다. UI instrumentation 소스는 `app.memoryvault.ui.MemoryVaultScreenTest`이며 초기 UNKNOWN, 편집 중 gate 전환, 저장 상태 복원, 읽기 전용 음성 원문과 명시적 저장을 확인합니다.

[MV-01 당시 실행 화면](.local/evidence/mv-01-bootstrap.png)과 당시 빌드 로그는 Git에서 제외한 로컬 자료 `.local/evidence/`에 있습니다. 공식 이미지 메타데이터 차이, `android.car` 런타임 선언 수정, 해시와 제한은 [환경 기록](docs/environment/pleos-memory-vault-v4.md)에 정리했습니다.

## Git에 포함하지 않는 파일

`local.properties`는 위 명령으로 각 환경에서 생성합니다. root `.local/`의 캐시·AVD·debug 서명키·검증 로그와 빌드 출력도 새로 생성하며 복사하지 않습니다. 개인 IDE 상태와 로컬 Kotlin/native 캐시는 제외합니다. 공용 `gradle.properties`, Gradle wrapper, 앱 소스·리소스는 공유합니다. `.env.example` 같은 템플릿에는 예시 값만 넣습니다. 실제 API 키·인증정보는 저장소와 APK에 넣지 않습니다.

앱은 키 없이 빌드·실행되며 방문·메모 저장, 차량 안전 gate, ON_DEVICE 음성 서비스와 최소 화면을 연결합니다. 사진 수집·공유·AI 호출과 자동 방문 연결은 아직 구현하지 않았습니다. AI 연결 대기 데이터가 있어도 분석 완료로 표시하지 않습니다. 전체 설계의 동작을 검증했다고 주장하지 않으며, `.local/evidence/`의 과거 화면·로그는 새 clone에 포함되지 않습니다.
