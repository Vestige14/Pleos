# 기억 저장소 · Pleos

현재 브랜치에는 **3A 저장 코어**(Room·시드 방문·직접 메모·음성 원문의 원자 저장·AI 대기 데이터)와 **3B 안전 정책·음성 세션 코어**가 구현되어 있습니다. 앱 화면은 초기 환경 안내 화면이며, 실제 STT 저장 경로와 차량 안전 연동을 포함한 **전체 3단계는 미완료**입니다. 완료 범위와 남은 조건은 [개발 상태](docs/development-status.md), 플랫폼 관측과 공식 문서 조사 결과는 [2단계 실측 기록](docs/environment/mv-02-platform-spikes.md)에 있습니다. 각 단계 이후 결과를 보고하고 중단하며, 다음 단계는 사용자 지시 후 진행합니다.

첫 릴리즈 목표는 **v0.1.0: F1~F10 + OpenAI 방문 연결·사용자 확인 + 통합 검증**입니다(2026-11-14 사용자 결정). 기존 3·5·7단계는 중간 개발 체크포인트이며, 8단계까지 전체 완료 기준을 충족했을 때 첫 릴리즈를 만듭니다. F11 전체 배지 확장·F12 사진 이동·F13 문장 다듬기·F14 링크 공유·F15 3D 기억 맵은 후속 범위입니다. 상세 수락 기준과 이전 버전 배정의 변경은 [첫 릴리즈 범위](docs/development-status.md#첫-릴리즈-범위--2026-11-14-사용자-결정)에 기록했습니다.

로컬 환경: JDK 17, Android SDK 35, AGP 8.13.2, Gradle 8.13, Kotlin 2.2.21. 앱은 `app.memoryvault`, minSdk 29, targetSdk 34입니다.

## 빌드

새 clone의 프로젝트 루트에서 실행합니다. JDK 17과 Android SDK를 먼저 설치합니다. 아래는 macOS 예시이며, 다른 운영체제에서는 JAVA_HOME과 ANDROID_HOME을 자신의 설치 경로로 설정합니다.

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_USER_HOME="$PWD/.local/android"
export GRADLE_USER_HOME="$PWD/.local/gradle-home"
mkdir -p "$ANDROID_USER_HOME" "$GRADLE_USER_HOME"
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
./gradlew --no-daemon --console=plain assembleDebug lintDebug testDebugUnitTest
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. 최초 실행에는 공개 의존성 다운로드가 필요합니다. 3B 추가 후 메인이 실행한 검증 결과는 JVM 단위 테스트 **48/48**, Lint **오류 0건·경고 13건**입니다. 실제 Room 테스트 **27/27**도 새 전용 AVD에서 통과했습니다. 재현 시에는 별도로 실행하며, 테스트 APK 준비와 명령은 [코어 검증 재현](docs/development-status.md#코어-검증-재현)을 따릅니다.

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

확인할 초기 화면: `기억 저장소`, `1단계 · 초기 환경 확인`, 음성·차량 상태 `미연동`, 기준일 `2026-11-08 · Asia/Seoul`. 저장 코어는 Room 테스트, 안전 정책·음성 세션 코어는 JVM 테스트에서 확인하며, 이를 조작하는 앱 UI는 아직 없습니다. 일반 앱 실행과 Room 검증에는 마이크가 필요하지 않습니다. 실제 발화 검증에서 마이크를 켜는 작업은 사용자 동의를 받은 [2단계 STT 재현 절차](docs/environment/mv-02-platform-spikes.md)에서만 수행합니다.

[MV-01 당시 실행 화면](.local/evidence/mv-01-bootstrap.png)과 당시 빌드 로그는 Git에서 제외한 로컬 자료 `.local/evidence/`에 있습니다. 공식 이미지 메타데이터 차이, `android.car` 런타임 선언 수정, 해시와 제한은 [환경 기록](docs/environment/pleos-memory-vault-v4.md)에 정리했습니다.

## Git에 포함하지 않는 파일

`local.properties`는 위 명령으로 각 환경에서 생성합니다. root `.local/`의 캐시·AVD·debug 서명키·검증 로그와 빌드 출력도 새로 생성하며 복사하지 않습니다. 개인 IDE 상태와 로컬 Kotlin/native 캐시는 제외합니다. 공용 `gradle.properties`, Gradle wrapper, 앱 소스·리소스는 공유합니다. `.env.example` 같은 템플릿에는 예시 값만 넣습니다. 실제 API 키·인증정보는 저장소와 APK에 넣지 않습니다.

앱은 키 없이 빌드·실행되며 방문·메모 저장과 AI 연결 대기 데이터 코어가 구현되어 있습니다. 실제 STT·앱 UI·차량 안전 통합, 사진·공유·AI 호출은 후속 범위이고 전체 설계의 동작은 아직 검증하지 않았습니다. `.local/evidence/`의 과거 화면·로그는 이 작업 환경의 자료로, 새 clone에는 포함되지 않습니다.
