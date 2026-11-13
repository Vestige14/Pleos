# 기억 저장소 · Pleos

현재 브랜치에는 Room·시드 방문·직접 메모·음성 원문의 원자 저장·AI 연결 대기 데이터를 다루는 **3A 저장 코어**가 구현되어 있습니다. 앱 화면은 초기 환경 안내 화면이며, 실제 STT 저장 경로와 차량 안전 연동을 포함한 **전체 3단계는 미완료**입니다. 사용자 요청에 따라 여기서 진행을 중단했습니다. 완료 범위와 재개 조건은 [개발 상태](docs/development-status.md), 플랫폼 관측과 제한은 [2단계 실측 기록](docs/environment/mv-02-platform-spikes.md)에 있습니다.

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

APK: `app/build/outputs/apk/debug/app-debug.apk`. 최초 실행에는 공개 의존성 다운로드가 필요합니다. 3A에 기록된 검증 결과는 JVM 단위 테스트 **8/8**, Lint **오류 0건·경고 11건**입니다. 실제 Room 테스트 **27개**는 전용 AVD에서 별도로 실행하며, 테스트 APK 준비와 명령은 [코어 검증 재현](docs/development-status.md#코어-검증-재현)을 따릅니다.

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
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 install --user 10 -r app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 shell am start -W --user 10 -n app.memoryvault/.MainActivity
```

확인할 초기 화면: `기억 저장소`, `1단계 · 초기 환경 확인`, 음성·차량 상태 `미연동`, 기준일 `2026-11-08 · Asia/Seoul`. 저장 코어는 위 Room 테스트에서 확인하며, 이를 조작하는 앱 UI는 아직 없습니다. 일반 앱 실행과 Room 검증에는 마이크가 필요하지 않습니다. 실제 발화 검증에서 마이크를 켜는 작업은 사용자 동의를 받은 [2단계 STT 재현 절차](docs/environment/mv-02-platform-spikes.md)에서만 수행합니다.

[MV-01 당시 실행 화면](.local/evidence/mv-01-bootstrap.png)과 당시 빌드 로그는 Git에서 제외한 로컬 자료 `.local/evidence/`에 있습니다. 공식 이미지 메타데이터 차이, `android.car` 런타임 선언 수정, 해시와 제한은 [환경 기록](docs/environment/pleos-memory-vault-v4.md)에 정리했습니다.

## Git에 포함하지 않는 파일

`local.properties`는 위 명령으로 각 환경에서 생성합니다. root `.local/`의 캐시·AVD·debug 서명키·검증 로그와 빌드 출력도 새로 생성하며 복사하지 않습니다. 개인 IDE 상태와 로컬 Kotlin/native 캐시는 제외합니다. 공용 `gradle.properties`, Gradle wrapper, 앱 소스·리소스는 공유합니다. `.env.example` 같은 템플릿에는 예시 값만 넣습니다. 실제 API 키·인증정보는 저장소와 APK에 넣지 않습니다.

앱은 키 없이 빌드·실행되며 방문·메모 저장과 AI 연결 대기 데이터 코어가 구현되어 있습니다. 실제 STT·앱 UI·차량 안전 통합, 사진·공유·AI 호출은 후속 범위이고 전체 설계의 동작은 아직 검증하지 않았습니다. `.local/evidence/`의 과거 화면·로그는 이 작업 환경의 자료로, 새 clone에는 포함되지 않습니다.
