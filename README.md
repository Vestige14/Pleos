# 기억 저장소 · Pleos

## 개발자·에이전트: 작업 전에 필수로 읽기

**[인수인계 문서](docs/HANDOFF.md)를 먼저 읽고, 루트 [AGENTS.md](AGENTS.md) 및 `docs/` 아래의 모든 MD·PDF 내용을 확인한 뒤 작업하세요.** 인수인계에는 원본 자료의 읽는 순서·우선순위, 현재 구현, 실행 방법, 남은 기능과 개발 방향이 정리돼 있습니다. PDF를 포함한 원본 자료를 요약만으로 대신하지 않습니다.

**매 push 전 `docs/HANDOFF.md` 갱신은 필수입니다.** 본인이 무엇을 개발했는지, 완료/미검증 범위와 실행·검증 결과, 남은 문제, 다음에 어떤 기능을 개발해야 하는지와 첫 작업을 적으세요. 문서만 수정한 push도 적용됩니다. 실제 비밀값을 제외하고 원본 자료와 운영 지침은 함께 유지합니다.

현재 main의 제품 코드는 2단계 기준선입니다. 인수인계가 설명하는 3단계 진행분은 작성 당시 별도 로컬 feature의 상태이며, 이번 문서 게시에 제품 소스는 포함하지 않았습니다. clone 후 실제 브랜치·파일을 확인하세요. Git clone은 파일을 내려받을 뿐 문서를 자동 실행하지 않으며, AGENTS.md 지원 에이전트는 루트 지침을 따라 읽습니다.

현재 main은 2단계 플랫폼 실험까지의 검증 기록과 초기 안내 앱을 보존합니다. 실험용 코드는 정리됐으며, 진행 중인 3단계 제품 구현 상태와 남은 작업은 [인수인계 문서](docs/HANDOFF.md)를 확인하세요.

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

APK: `app/build/outputs/apk/debug/app-debug.apk`. 최초 실행에는 공개 의존성 다운로드가 필요합니다. 부트스트랩에는 단위 테스트 소스가 없어 `testDebugUnitTest`는 `NO-SOURCE`입니다. Lint 결과는 오류 0건·경고 12건입니다.

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
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 emu avd hostmicon
```

확인할 화면: `기억 저장소`, `1단계 · 초기 환경 확인`, 음성·차량 상태 `미연동`, 기준일 `2026-11-08 · Asia/Seoul`. 마이크 설정은 켰으나 실제 발화·macOS 마이크 허용·STT는 2단계 확인 대상입니다.

[실제 실행 화면](.local/evidence/mv-01-bootstrap.png)과 빌드 로그는 Git에서 제외한 `.local/evidence/`에 있습니다. 공식 이미지 메타데이터 차이, `android.car` 런타임 선언 수정, 해시와 제한은 [환경 기록](docs/environment/pleos-memory-vault-v4.md)에 정리했습니다.

## Git에 포함하지 않는 파일

`local.properties`는 위 명령으로 각 환경에서 생성합니다. root `.local/`의 캐시·AVD·debug 서명키·검증 로그와 빌드 출력도 새로 생성하며 복사하지 않습니다. 개인 IDE 상태와 로컬 Kotlin/native 캐시는 제외합니다. 공용 `gradle.properties`, Gradle wrapper, 앱 소스·리소스는 공유합니다. `.env.example` 같은 템플릿에는 예시 값만 넣습니다. 실제 API 키·인증정보는 저장소와 APK에 넣지 않습니다.

이 초기 앱은 키 없이 빌드·실행됩니다. 음성·차량 연동과 저장·공유·AI 기능은 후속 개발 범위이며, 아직 전체 설계의 동작을 보증하는 단계는 아닙니다. `.local/evidence/`의 화면·로그 링크는 이 작업 환경의 검증 자료로, 새 clone에는 포함되지 않습니다.
