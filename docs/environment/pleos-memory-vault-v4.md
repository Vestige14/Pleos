# MV-01 Pleos 초기 환경 기록

검증일: 2026-11-13, Asia/Seoul. 범위는 승인된 v4 로드맵의 1단계 부트스트랩이다. 기존 설계 문서와 기존 AVD는 수정하지 않았다. 실제 음성·차량 API 검증은 2단계에 남긴다.

## 고정 버전과 근거

| 항목 | 설정·실제 확인 |
| --- | --- |
| 앱 | `app.memoryvault`, versionCode 1, versionName `0.0.1-bootstrap` |
| Android | minSdk 29 / targetSdk 34 / compileSdk 35 / Build Tools 35.0.0 |
| Java | Temurin 17.0.21+9, JVM target 17 |
| AGP / Gradle | 8.13.2 / 8.13 |
| Kotlin / Compose compiler plugin / serialization plugin | 모두 2.2.21 |
| KSP | 2.2.21-2.0.4, KSP2 |
| Compose BOM | 2025.04.01 → 실제 UI 1.8.0 / Material3 1.3.2 |
| Activity Compose | 1.10.1 |
| Room runtime / ktx / compiler | 2.7.2 |
| kotlinx serialization JSON | 1.8.1 |
| ExifInterface | 1.4.1 |
| Navigation Compose | 2.8.9 |
| Android Emulator | 37.2.13.0, build 16740729 |

AGP의 [공식 호환표](https://developer.android.com/build/releases/agp-8-13-0-release-notes)는 Gradle 8.13·JDK 17·Build Tools 35.0.0을 명시한다. Kotlin 2.2.21의 [공식 릴리즈 기록](https://kotlinlang.org/docs/releases.html), [KSP 고정 버전](https://github.com/google/ksp/releases/tag/2.2.21-2.0.4), [Kotlin 2 이상 Compose plugin 안내](https://developer.android.com/jetpack/androidx/releases/compose-kotlin)를 확인했다. 최신 버전으로 임의 변경하지 않고 위 조합을 실제 해석·빌드했다.

라이브러리 근거: [Compose BOM 발표](https://android-developers.googleblog.com/2025/04/whats-new-in-jetpack-compose-april-25.html), [Activity 1.10.1](https://developer.android.com/jetpack/androidx/releases/activity#1.10.1), [Room 2.7.2 및 KSP2 지원](https://developer.android.com/jetpack/androidx/releases/room#2.7.2), [serialization 1.8.1](https://github.com/Kotlin/kotlinx.serialization/releases/tag/v1.8.1), [ExifInterface 1.4.1](https://developer.android.com/jetpack/androidx/releases/exifinterface#1.4.1), [Navigation 2.8.9](https://developer.android.com/jetpack/androidx/releases/navigation#2.8.9). 실제 선택된 runtime 버전은 AGP의 생성된 Lint 의존성 모델에서 추출해 `.local/evidence/resolved-library-versions.json`에 남겼다. Room 등은 다음 승인 단계 준비용 의존성만 추가했으며 DB·네트워크 동작은 없다.

Gradle wrapper JAR는 공식 Gradle v8.13.0 소스 저장소에서 내려받아 [공식 wrapper SHA-256](https://services.gradle.org/distributions/gradle-8.13-wrapper.jar.sha256)과 일치함을 확인했다. 배포 ZIP도 [공식 SHA-256](https://services.gradle.org/distributions/gradle-8.13-bin.zip.sha256)을 wrapper 설정에 고정했다.

- Wrapper JAR: `81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f`
- Gradle 배포 ZIP: `20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`

## SDK와 공식 Pleos 이미지

- SDK: `$ANDROID_HOME` (macOS 기본 위치 예시: `$HOME/Library/Android/sdk`) — 설치된 SDK와 시스템 이미지는 읽기 전용으로 재사용했다.
- JDK: `$JAVA_HOME` (macOS에서는 `/usr/libexec/java_home -v 17`로 확인).
- 설치 이미지: `system-images;android-34;pleos_car_emulator;arm64-v8a`.
- [공식 시작 안내](https://document.pleos.ai/en/docs/pleos-only/getting-started)는 Mac용 [공식 배포 XML](https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml), API 34, Cold boot, RAM 4GB를 안내한다.
- 해당 XML을 직접 다운로드해 설치된 `package.xml`과 대조했다. 두 파일 모두 API 34 / arm64-v8a / vendor `pleos` / revision 2 / `Pleos Connect v2.1.0`을 가리킨다.
- 이미지 내부의 `source.properties`는 revision 1 / vendor `42dot`으로 기록돼 있다. 외부 package 메타데이터와 다른 이 표기를 보정하거나 설치 파일을 수정하지 않았다.
- 공식 XML의 archive 크기는 4,669,762,816 bytes, SHA-1은 `24d28eb2f18eba5e87c861a7eb2bdccebb049419`이다. 원본 ZIP 4.67GB를 재다운로드하지 않았으므로 이 체크섬과 설치 이미지 바이트를 직접 대조한 것은 아니다. 확인한 출처는 공식 안내 → 공식 배포 XML → 설치 package 메타데이터의 일치이며, 완전한 설치 바이트 증명을 주장하지 않는다.
- 새 기기에서 확인한 fingerprint: `Pleos/crp_avd_arm64/crp_avd_arm64:14/RELEASE.release_connect_v2.1.0/release_connect_v2.1.0:user/test-keys`.

## AVD 격리와 생성

| 항목 | 실제 값 |
| --- | --- |
| 새 AVD | `Pleos_MemoryVault_API34` |
| 저장 위치 | 프로젝트 `.local/avd/Pleos_MemoryVault_API34.avd` |
| 장치 프로필 | `Pleos_Connect_v2_0` / 표시 이름 Pleos Connect v2.1.0 |
| 해상도 | 2560 × 1440, 160 dpi |
| serial | `emulator-5580` |
| ADB 서버 | `tcp:localhost:5038` — 기존 기본 서버와 분리 |
| RAM | config 4096 MB / guest `MemTotal: 4016816 kB` |
| 마이크 | `hw.audioInput=yes`, 콘솔 `avd hostmicon` 응답 `OK` |
| 부팅 | `fastboot.forceColdBoot=yes`, `fastboot.forceFastBoot=no`, `-no-snapshot` |
| 부팅 확인 | `sys.boot_completed=1`, 첫 boot 23018 ms |
| 현재 Android 사용자 | 10 |

새 AVD 이름과 경로가 없음을 확인한 뒤 다음 명령으로 생성했다. 기존 이름이 있으면 덮어쓰지 말고 새 이름을 정해야 한다. `--force`, 기존 AVD 복제, 기존 userdata나 snapshot 재사용은 하지 않았다.

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_USER_HOME="$PWD/.local/android"
export ANDROID_EMULATOR_HOME="$PWD/.local/android"
export ANDROID_AVD_HOME="$PWD/.local/avd"
export ADB_SERVER_SOCKET=tcp:localhost:5038
mkdir -p "$ANDROID_USER_HOME" "$ANDROID_AVD_HOME"
printf 'no\n' | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd \
  --name Pleos_MemoryVault_API34 \
  --package 'system-images;android-34;pleos_car_emulator;arm64-v8a' \
  --device Pleos_Connect_v2_0 \
  --path "$ANDROID_AVD_HOME/Pleos_MemoryVault_API34.avd"
```

생성 후 새 AVD의 `config.ini`에서 아래 값으로 설정했다. 다음 Python 구문은 해당 새 AVD 설정만 갱신하며 기존 사용자 AVD에는 적용하지 않는다.

```sh
python3 - <<'PY'
from pathlib import Path
p = Path('.local/avd/Pleos_MemoryVault_API34.avd/config.ini')
values = {}
for line in p.read_text().splitlines():
    if '=' in line:
        k, v = line.split('=', 1)
        values[k.strip()] = v.strip()
values.update({
    'hw.ramSize': '4096', 'hw.audioInput': 'yes', 'hw.keyboard': 'yes',
    'fastboot.forceColdBoot': 'yes', 'fastboot.forceFastBoot': 'no',
    'fastboot.forceChosenSnapshotBoot': 'no',
})
p.write_text(''.join(f'{k}={v}\n' for k, v in values.items()))
PY
```

`userdata-qemu.img`가 첫 부팅 전에는 없었고 새 경로에서 생성됐음을 확인했다. 시스템 이미지가 제공한 초기 `userdata.img` 템플릿을 사용했으며 기존 사용자 AVD의 데이터는 가져오지 않았다. 설치 전 `app.memoryvault`는 없었고, 사용자 0·10의 계정 수는 각각 0, 현재 사용자 10의 MediaStore 사진 수는 0이었다. 이것은 조회한 계정·사진 범위의 결과이며 모든 저장 매체를 포렌식 검사했다는 의미는 아니다.

기존 `~/.android/avd/Pleos_Connect_2_1_API34*`의 25개 파일에 대해 전후 size/mtime 목록 및 config/ini SHA-256을 비교해 변동 없음을 확인했다. 큰 userdata 파일 전체 내용 해시는 계산하지 않았다. 비교 자료는 `.local/evidence/existing-avd-before.json`, `existing-avd-after.json`이다.

## 실행과 검수

프로젝트 루트에서 위 환경 변수를 설정하고 실행한다. 이미 실행 중인 동일 AVD는 중복 실행하지 않는다.

```sh
"$ANDROID_HOME/emulator/emulator" -avd Pleos_MemoryVault_API34 \
  -port 5580 -memory 4096 -no-snapshot -no-boot-anim -no-metrics
```

다른 터미널에서도 같은 환경 변수를 설정한 뒤, [README](../../README.md)의 전체 빌드 명령으로 생성한 APK를 설치한다. 현재 사용자 10은 새 이미지에서 직접 조회한 값이다. 다른 환경에서는 `adb -s emulator-5580 shell am get-current-user`로 다시 확인한다.

```sh
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 emu avd name
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 shell getprop sys.boot_completed
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 install --user 10 -r app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 shell am start -W --user 10 -n app.memoryvault/.MainActivity
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 emu avd hostmicon
"$ANDROID_HOME/platform-tools/adb" -s emulator-5580 exec-out screencap -p > .local/evidence/mv-01-bootstrap.png
```

실제 설치 결과는 `Success`, Activity launch는 `Status: ok`, `LaunchState: COLD`, `TotalTime: 817`이었다. 현재 resumed Activity가 `u10 app.memoryvault/.MainActivity`인 것도 확인했다. [2560 × 1440 실제 화면](../../.local/evidence/mv-01-bootstrap.png)을 직접 열어 제목·한국어 상태 문구·고정 기준일이 잘리지 않고 보이는지 검사했다. 시스템 좌측 차량 화면의 주차·속도 표시는 OS 기본 UI이며 이 앱이 차량에 연결됐다는 근거가 아니다.

검수 기준은 `기억 저장소`, `1단계 · 초기 환경 확인`, 음성/차량 `미연동`, `2026-11-08 · Asia/Seoul` 표시다. 정적 설정 화면 외에 사용자 데이터·저장 기능·권한 요청·원격 API 동작은 추가하지 않았다.

## android.car 설치 오류 조사와 결정

첫 APK는 manifest에 런타임 `android.car` 공유 라이브러리를 `required=true`로 선언해 `INSTALL_FAILED_MISSING_SHARED_LIBRARY`로 설치가 거부됐다. 원인은 다음 읽기 전용 증거로 좁혔다.

- `pm list libraries`에 `android.car`는 없고 `ai.pleos.playground.car`, `car-frameworks-service`가 있다.
- `android.hardware.type.automotive` 기능과 `car_service: [android.car.ICar]`, 차량 HAL 서비스가 존재한다.
- BOOTCLASSPATH에는 `/system/framework/android.car.builtin.jar`, `/apex/com.android.car.framework/javalib/android.car-module.jar`가 있다.

[Android 공식 manifest 설명](https://developer.android.com/guide/topics/manifest/uses-library-element)에 따라 필수 공유 라이브러리 선언이 설치 거부 조건임을 확인했다. 메인은 부트스트랩에서 런타임 선언을 `required=false`로 변경하도록 승인했다. Gradle의 컴파일용 `useLibrary("android.car")`와 automotive 필수 기능은 유지했다. 수정 뒤 전체 빌드와 새 기기 설치·화면 실행을 확인했다. 이는 실제 Car 클래스 연결·차량 상태 수신 성공을 뜻하지 않는다. 해당 연결 및 UNKNOWN 처리·공식 Pleos SDK 경로는 2단계에서 검증해야 한다.

## 검증 결과와 남은 제한

- 전체 명령: `./gradlew --no-daemon --console=plain assembleDebug lintDebug testDebugUnitTest` — 위 JDK/SDK 및 프로젝트 로컬 Gradle home 사용, 변경 후 exit 0 / `BUILD SUCCESSFUL in 14s` / 51 tasks. 메인도 같은 소스를 별도로 재검증한다.
- 단위 테스트는 생성된 스캐폴드에 소스가 없어 `testDebugUnitTest NO-SOURCE`다. 테스트 개수나 기능 테스트 통과를 만들어 기록하지 않았다.
- TDD: 이 작업은 승인된 스캐폴드·설정 예외다. 실제 저장·판단·통신 로직을 구현하지 않았으며 이후 의미 있는 동작 추가에는 TDD가 필요하다.
- Lint: 오류 0, 경고 12. OldTargetApi 1 / AndroidGradlePluginVersion 1 / GradleDependency 8 / NewerVersionAvailable 1 / DataExtractionRules 1. 승인된 SDK·버전 고정에 관한 업데이트 경고와 `allowBackup=false`의 Android 12 이상 전송 규칙 경고를 남겼으며 검사를 끄거나 baseline으로 숨기지 않았다.
- SDK XML v3/v4 해석 경고와 초기 빌드의 `libandroidx.graphics.path.so` 디버그 심볼 strip 불가 메시지가 있었다. 빌드·설치는 성공했으며 SDK·NDK를 임의 업데이트하지 않았다.
- 에뮬레이터 로그에 초기 ColorBuffer/Qt 경고와 첫 ADB 연결 경합이 있었다. 이후 boot 완료·현재 Activity·실제 화면을 확인했다. 경고가 없었다고 보고하지 않는다.
- AVD 설정 및 hostmicon 명령은 확인했지만 실제 오디오 샘플, macOS 마이크 권한 허용, STT 인식은 검증하지 않았다. macOS가 요청하면 실제 Emulator 실행 주체에 마이크를 허용해야 한다. 앱은 아직 RECORD_AUDIO 권한을 요청하지 않는다.
- 네트워크 다운로드/빌드/에뮬레이터는 샌드박스 DNS·하이퍼바이저·로컬 포트 제한 때문에 목적별 escalation으로 실행했다. 제한을 우회하거나 기존 AVD를 대신 사용하지 않았다.

현재 소스 및 실행 산출물 식별:

| 대상 | SHA-256 |
| --- | --- |
| 앱·Gradle·wrapper 15개 파일 해시 목록 `.local/evidence/source-files.sha256` 자체의 해시 | `2a2c436fda8a582c9f692afda236af2d82b298c1f880c76509b616e60a907111` |
| 실행 APK (27,681,611 bytes) | `f9c57c267ffa17d9c5bee3a377dbf0a0a39208582de168989682714810995d1d` |
| 실제 화면 PNG | `9a87972beee14c904558edc483e60cbcf0dfba40bb7e286b6dde88f3b300620d` |

APK 원본과 설치용 `.local/evidence/mv-01-bootstrap.apk`의 해시가 일치했다. 소스 목록 해시는 문서·로컬 경로 설정을 제외한 빌드 입력을 식별하는 보조 자료이며 Git 커밋 해시를 대신하지 않는다.

API 키·SDK 키·토큰·개인 서명 자격증명은 추가하지 않았다. `.local/`, `local.properties`, 빌드 출력은 Git 제외 대상이다. 현재 변경과 APK·로그·화면의 범위 한정 비밀정보 검사를 별도 수행해 task report에 기록한다. 전체 Git 이력·전송 blob을 포함하는 매 push 직전 감사는 아직 수행하지 않았으며 push 승인도 받지 않았다.
