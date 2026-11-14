# 개발 환경 설정과 실행

처음 저장소를 받는 담당자의 환경 준비 안내입니다. `AGENTS.md`, [인수인계](HANDOFF.md)와 `docs/`의 모든 MD·PDF를 먼저 읽고, 기능 시험은 [TESTING](TESTING.md)으로 이어 갑니다. 현재 사진 연결을 포함한 4단계 개발 체크포인트입니다. 전체 사진 권한 수락 범위·알려진 UI 검사 실패 및 후속 단계는 [인수인계](HANDOFF.md), 사진 실행은 [사진 기능 안내](features/photo-linking.md)를 따릅니다. 첫 릴리즈 완료를 뜻하지 않습니다.

## 1. 저장소와 환경 확인

처음 받는 경우에만 실행합니다. 이미 checkout이 있으면 그 작업과 로컬 변경을 보존하고 재사용합니다. 독립 clone 검증을 자동으로 수행하라는 절차가 아닙니다.

```sh
git clone https://github.com/chowonbhin/Pleos.git
cd Pleos
git status --short
git branch -a
git log -5 --oneline
```

실제 파일이 인수인계의 제품 코드 기준을 포함하는지 확인합니다. 없는 로컬 feature가 원격에도 있다고 가정하거나 같은 기능을 중복 구현하지 않습니다.

| 항목 | 현재 기준 |
| --- | --- |
| JDK | 17 |
| Android SDK | Platform 35, Build Tools 35.0.0, Platform Tools |
| Gradle / AGP | wrapper 8.13 / 8.13.2 |
| Kotlin / Compose compiler | 2.2.21 |
| 앱 | `app.memoryvault`, minSdk 29 / targetSdk 34 / compileSdk 35 |
| 실제 기능 시험 | 공식 Pleos Connect v2.1.0 API34, 호스트 CPU에 맞는 이미지, RAM 4GB, cold boot |
| 음성 | `ai.pleos.playground:SpeechToText:3.3.1`, ON_DEVICE 한국어 |

현재 확인 환경은 macOS ARM64입니다. 다른 OS에서는 설치 위치·이미지 ABI·실제 도구 지원을 확인하고 그 차이를 기록합니다. Windows의 wrapper는 `gradlew.bat`입니다. 기존에 맞는 도구는 재사용하고 다른 프로젝트의 전역 설정을 바꾸지 않습니다.

Pleos 이미지·장치 프로필은 [공식 시작 안내](https://document.pleos.ai/docs/pleos-only/getting-started)와 [환경 구성 기록](environment/pleos-memory-vault-v4.md)을 따릅니다. 현재 배포본과 설치 메타데이터를 대조하고, 버전 차이를 근거 없이 수정하지 않습니다. 최초 설치의 로그인·라이선스 동의·호스트 OS 마이크 허용 등 사용자 조작이 필요한 부분은 이유와 조작을 구체적으로 안내합니다.

## 2. 프로젝트 로컬 설정과 빌드

다음은 JDK·SDK가 준비된 macOS 예시입니다. 실제 경로와 기존 `local.properties`를 먼저 확인합니다.

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_USER_HOME="$PWD/.local/android"
export GRADLE_USER_HOME="$PWD/.local/gradle-home"
export MV_ADB="$ANDROID_HOME/platform-tools/adb"
mkdir -p "$ANDROID_USER_HOME" "$GRADLE_USER_HOME"
test -f local.properties || printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
./gradlew --no-daemon --console=plain assembleDebug assembleDebugAndroidTest
```

첫 빌드에는 Gradle과 공개 의존성 다운로드가 필요할 수 있습니다. 캐시가 준비되지 않은 환경에 `--offline`부터 붙이지 않습니다. 이 단계는 Pleos 등록 키·OpenAI 키·백엔드를 요구하지 않습니다. 키를 소스나 APK에 넣어 다운로드 오류를 해결하지 않습니다.

생성 경로:

- 제품: `app/build/outputs/apk/debug/app-debug.apk`
- 기기 테스트: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`

서명키는 이 환경의 `.local/android/debug.keystore`에 준비됩니다. 다른 담당자의 키·AVD·캐시·개인 설정을 복사하지 않습니다. debug도 SDK 원문 로그 제거를 위해 R8과 non-debuggable 설정을 사용하므로 debugger·`run-as`를 전제로 하지 않습니다. 실제 발화 전 DEX 확인은 TESTING의 필수 절차입니다.

## 3. 전용 Pleos AVD 준비

설치된 AVD와 사용 중인 ADB·emulator 포트를 확인하고, 다른 작업과 충돌하지 않는 전용 기기를 선택합니다. 새 환경을 만들 때 기존 AVD의 userdata·사진·DB·스냅샷을 복제하지 않습니다. AVD 생성과 프로필 선택은 [환경 기록](environment/pleos-memory-vault-v4.md)의 절차를 실제 호스트에 맞게 적용합니다. 과거의 이름·serial·포트는 기본값이 아닙니다.

아래 변수는 실제 전용 구성에 맞게 지정합니다. ADB 서버 포트와 emulator 짝수 포트·다음 포트가 비어 있어야 합니다.

```sh
export ANDROID_EMULATOR_HOME="$PWD/.local/android"
export ANDROID_AVD_HOME="$PWD/.local/avd"
: "${MV_AVD_NAME:?준비한 전용 AVD 이름}"
: "${MV_ADB_PORT:?선택한 전용 ADB 서버 포트}"
: "${MV_EMULATOR_PORT:?선택한 emulator 짝수 포트}"
export ADB_SERVER_SOCKET="tcp:localhost:$MV_ADB_PORT"
"$ANDROID_HOME/emulator/emulator" -avd "$MV_AVD_NAME" \
  -port "$MV_EMULATOR_PORT" -memory 4096 -no-snapshot -no-boot-anim -no-metrics
```

이미 같은 기기가 실행 중이면 중복 실행하지 않습니다. 기기 설정에서 `hw.audioInput=yes`와 cold boot를 확인합니다. 입력 설정을 바꿨다면 같은 AVD를 데이터 보존 상태로 재시작하고 적용을 확인합니다. 실제 발화가 준비되기 전에는 호스트 마이크 입력을 켜지 않습니다.

실행 터미널을 유지하고, 같은 환경변수를 설정한 별도 터미널에서 연결을 확인합니다.

```sh
"$MV_ADB" devices -l
: "${MV_SERIAL:?devices에서 확인한 전용 기기의 실제 ADB transport serial}"
"$MV_ADB" -s "$MV_SERIAL" shell getprop sys.boot_completed
MV_USER_ID="$("$MV_ADB" -s "$MV_SERIAL" shell am get-current-user | tr -d '\r')"
export MV_USER_ID
MV_CONSOLE_SERIAL="emulator-$MV_EMULATOR_PORT"
export MV_CONSOLE_SERIAL
"$MV_ADB" -s "$MV_CONSOLE_SERIAL" emu avd name
"$MV_ADB" -s "$MV_SERIAL" shell pm path --user "$MV_USER_ID" app.memoryvault
```

`sys.boot_completed=1`, AVD 이름과 현재 사용자가 맞는지 확인합니다. 일반 ADB transport는 `127.0.0.1:<port>`이고 emulator console alias는 `emulator-<짝수 port>`인 경우가 있습니다. **shell/install/reboot에는 실제 transport를**, `emu avd ...`에는 이름이 확인된 console alias를 사용합니다. TCP 연결이 필요한 경우에만 자신이 시작한 emulator의 ADB 포트에 연결하고 `devices -l`을 다시 확인합니다.

## 4. 설치·권한·첫 화면

`pm path`가 비어 있고 대상이 새 전용 기기임을 확인한 경우에만 최초 설치합니다.

```sh
"$MV_ADB" -s "$MV_SERIAL" install --user "$MV_USER_ID" app/build/outputs/apk/debug/app-debug.apk
"$MV_ADB" -s "$MV_SERIAL" install --user "$MV_USER_ID" app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$MV_ADB" -s "$MV_SERIAL" shell am start -W --user "$MV_USER_ID" -n app.memoryvault/.MainActivity
```

기존 앱이 있으면 서명·기존 데이터·소유자를 먼저 확인합니다. **같은 서명임을 확인한 업데이트만 `install -r --user ...`로 진행**합니다. 서명 충돌을 해결하려 기존 앱을 uninstall/clear하지 않습니다. 다른 키로 만든 빌드는 별도 새 AVD에서 확인합니다.

첫 화면이 UNKNOWN이면 차량 권한과 신호 준비부터 확인합니다. 공식 Virtual Console에서 P·속도0을 직접 확인한 뒤 아래 개발용 명령으로 외부 앱 설정을 엽니다. 앱 자체가 UNKNOWN을 주차로 바꾸거나 권한창을 자동 실행하는 경로가 아닙니다.

```sh
"$MV_ADB" -s "$MV_SERIAL" shell am start -W --user "$MV_USER_ID" \
  -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:app.memoryvault
```

시스템의 앱 권한에서 **차량 속도 권한(CAR_SPEED)**을 확인해 허용합니다. 현재 이미지에서는 ‘위치 정보’ 그룹으로 보일 수 있으며, 앱이 별도 위치 수집 권한을 추가했다는 뜻은 아닙니다. 앱으로 돌아가 `차량 상태 다시 확인`을 누릅니다. 계속 UNKNOWN이면 권한·Car 서비스·기어·속도·UX 준비를 확인하고 잠금을 유지합니다.

신뢰된 P에서 하루 초안과 시드 방문 3곳이 보이면 `음성 권한 설정`으로 마이크·알림 권한을 명시적으로 준비합니다. 일반 앱 목록의 **기억 저장소 · 녹음**을 열고 오른쪽 세 점 창 막대를 왼쪽으로 끌어 내비와 분할합니다. `시작 알림 켜기`는 기존 알림 방식의 선택 사항이며 패널 시작에는 필요하지 않습니다. 실제 발화·분할 진입·복원·시험 후 정리는 [TESTING](TESTING.md)에 따릅니다.

## 5. 결과 보고와 게시

사용한 소스 커밋, JDK/SDK·이미지, AVD·실제 transport/console alias·user, APK 해시·경로, 확인한 화면·기능과 미검증 범위를 보고합니다. 빌드 성공만으로 실제 음성이나 3단계 전체 완료를 선언하지 않습니다.

현재 정상 알림 진입 문제는 [이슈 #1](https://github.com/chowonbhin/Pleos/issues/1)입니다. APK 설치·메모 저장과 별도로 추적합니다. 광범위 logcat·음성 원문·인증값을 수집하지 않습니다. 오류 대응은 TESTING의 실패 분류를 따릅니다.

중간 작업은 변경·직접 영향·필수 안전 검사를 수행하고, 단계/릴리즈 완료 때 전체 필수 검증을 수행합니다. 문서만 바뀌었으면 문서 내용·링크·자료 포함·비밀정보를 확인합니다. 게시 전에는 `AGENTS.md`의 로컬 main pull·merge·검증·인수인계 갱신·비밀정보 점검과 사용자 승인 범위를 확인합니다. 이번 3단계의 검증 결과와 새 환경에서 실제로 수행한 검사를 구분합니다. 첫 릴리즈는 후속 단계의 전체 수락 조건을 충족한 뒤 만들며, 환경 준비 요청으로 다음 단계나 게시를 자동 실행하지 않습니다.
