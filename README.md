# 기억 저장소 · Pleos

현재는 MV-01 초기 환경 확인 단계입니다. 새 Pleos AVD에서 Kotlin·Compose 앱의 한국어 안내 화면을 실행했습니다. 음성 인식, 차량 상태 연결, 방문·메모·사진·공유 기능은 다음 단계에서 구현합니다.

로컬 환경: JDK 17, Android SDK 35, AGP 8.13.2, Gradle 8.13, Kotlin 2.2.21. 앱은 `app.memoryvault`, minSdk 29, targetSdk 34입니다.

## 빌드

프로젝트 루트에서 실행합니다. JDK 17과 Android SDK를 설치하고 아래 macOS 환경 탐색 예시를 사용합니다.

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_USER_HOME="$PWD/.local/android"
export GRADLE_USER_HOME="$PWD/.local/gradle-home"
mkdir -p "$ANDROID_USER_HOME" "$GRADLE_USER_HOME"
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
./gradlew --no-daemon --console=plain assembleDebug lintDebug testDebugUnitTest
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. 최초 실행에는 공개 의존성 다운로드가 필요합니다. 부트스트랩에는 단위 테스트 소스가 없어 `testDebugUnitTest`는 `NO-SOURCE`입니다. Lint 결과는 오류 0건·경고 12건입니다.

## 새 AVD에서 확인

이 Mac에 별도로 만든 AVD만 사용합니다. 기존 `Pleos_Connect_2_1_API34`는 사용하거나 변경하지 않습니다. AVD 생성 과정과 다른 환경에서의 준비 방법은 [환경 기록](docs/environment/pleos-memory-vault-v4.md)에 있습니다.

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
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 emu avd hostmicon
```

확인할 화면: `기억 저장소`, `1단계 · 초기 환경 확인`, 음성·차량 상태 `미연동`, 기준일 `2026-11-08 · Asia/Seoul`. 마이크 설정은 켰으나 실제 발화·macOS 마이크 허용·STT는 2단계 확인 대상입니다.

[실제 실행 화면](.local/evidence/mv-01-bootstrap.png)과 빌드 로그는 Git에서 제외한 `.local/evidence/`에 있습니다. 공식 이미지 메타데이터 차이, `android.car` 런타임 선언 수정, 해시와 제한은 [환경 기록](docs/environment/pleos-memory-vault-v4.md)에 정리했습니다.
