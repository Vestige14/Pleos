# HAM

방문 장소·사진·음성 기록을 기억으로 연결하고, 고른 기록을 공유하는 Pleos 차량용 앱입니다.

## 실행 방법

1. 아래 소스를 받거나 Android Studio에서 저장소를 엽니다.

   ```sh
   git clone https://github.com/chowonbhin/Pleos.git
   cd Pleos
   ```

2. JDK 17과 Android SDK Platform 35, Build Tools 35.0.0, Platform Tools를 설치합니다. 프로젝트 루트의 `local.properties`에 자신의 SDK 경로를 설정합니다.

   ```properties
   sdk.dir=/자신의/Android/sdk/경로
   ```

3. 공식 Pleos Connect 에뮬레이터를 준비하고 실행한 뒤 테스트용 APK를 빌드합니다. 최초 빌드에는 의존성 다운로드를 위한 인터넷 연결이 필요합니다.

   ```sh
   ./gradlew :app:assembleDebug
   ```

   Windows에서는 `gradlew.bat`을 사용합니다. 생성되는 로컬 서명키는 `.local/android/debug.keystore`에 보관됩니다.

4. `adb devices -l`에서 대상 기기를 확인하고, 현재 Android 사용자에 설치합니다. `<SERIAL>`과 `<USER_ID>`는 실제 조회한 값으로 바꿉니다.

   ```sh
   adb devices -l
   adb -s <SERIAL> shell am get-current-user
   adb -s <SERIAL> install -r --user <USER_ID> app/build/outputs/apk/debug/app-debug.apk
   adb -s <SERIAL> shell am start --user <USER_ID> -n app.memoryvault/.MainActivity
   ```

   기존 설치와 서명이 다르면 별도 AVD를 사용합니다. 앱 목록에는 **HAM**, **HAM · 녹음**이 표시됩니다. 주/정차 상태에서 기록·사진·공유를 열고, 주행 중 녹음은 녹음 전용 진입점을 사용합니다. 내비와 함께 보려면 Pleos 창 막대로 수동 분할합니다.

## 테스트 환경 및 필요한 설정

| 항목 | 환경 |
| --- | --- |
| 개발·확인 OS | macOS / Apple Silicon ARM64 |
| Java | JDK 17 |
| Android | minSdk 29 / targetSdk 34 / compileSdk 35 |
| 빌드 도구 | Gradle Wrapper 8.13 / Android Gradle Plugin 8.13.2 / Kotlin 2.2.21 |
| 음성 인식 | Pleos SpeechToText 3.3.1, 한국어 ON_DEVICE |
| 내비 연동 | Pleos NaviHelper 3.0.5 |
| AI 백엔드 | Python 3.11, `backend/requirements.lock`의 고정 의존성 |

- **차량 상태:** 공식 Virtual Console을 사용할 에뮬레이터에 연결하고 기어·속도를 설정합니다. 최초 실행 시 시스템 앱 권한에서 차량 속도 권한을 허용합니다. 해당 이미지에서 위치 권한 그룹으로 표시될 수 있습니다.
- **음성:** 앱의 마이크·음성 인식·알림 권한을 준비합니다. 호스트 OS의 마이크 접근 허용과 에뮬레이터 입력 마이크 연결도 필요합니다.
- **사진:** 앱에서 사진 접근 권한을 허용하고 에뮬레이터의 사진을 사용합니다.
- **AI:** 아래 로컬 서버 설정이 필요합니다. 녹음·로컬 저장에는 OpenAI 키가 필요하지 않습니다. 키와 세션 토큰은 저장소 및 APK에 포함하지 않습니다.

### AI 서버 연결

저장소 루트에서 실행 환경을 준비합니다.

```sh
python3.11 -m venv backend/.venv
backend/.venv/bin/python -m pip install -r backend/requirements.lock
backend/.venv/bin/python -m pip install --no-build-isolation --no-deps -e backend
cd backend
```

아래 명령을 대화형 터미널에서 실행합니다. 세션 토큰은 영문·숫자·`_`·`-`로 구성된 32~256자 값을 준비하고 앱 설정에도 같은 값을 입력합니다. 모델 ID에는 자신의 OpenAI 계정에서 사용 가능한 모델을 지정합니다. 실제 AI 요청에는 해당 계정의 사용 요금이 발생합니다.

```sh
.venv/bin/python -c '
import getpass, os, uvicorn
os.environ["OPENAI_API_KEY"] = getpass.getpass("OpenAI API 키: ")
os.environ["MEMORY_LINK_SESSION_TOKEN"] = getpass.getpass("세션 토큰: ")
os.environ["OPENAI_LINK_MODEL"] = input("모델 ID: ").strip()
os.environ["OPENAI_LINK_REASONING_EFFORT"] = "low"
uvicorn.run("app.main:create_app", factory=True, host="127.0.0.1", port=8000,
            workers=1, access_log=False, log_level="warning")
'
```

다른 터미널에서 연결합니다. 8000번 포트를 다른 서비스가 사용 중이면 서버와 아래 연결 포트, 앱 주소를 함께 변경합니다.

```sh
adb -s <SERIAL> reverse --no-rebind tcp:8000 tcp:8000
```

주/정차 상태의 앱 **설정 → AI 연결 설정**에 `http://127.0.0.1:8000`과 위 세션 토큰을 저장합니다. OpenAI API 키는 서버에서만 사용합니다. `.env`는 자동 로드되지 않습니다. 로컬 HTTP 연결은 debug 빌드에서만 지원하며 release 빌드에는 HTTPS 서버가 필요합니다.

## Pleos Connect 에뮬레이터 버전과 화면 규격

| 항목 | 규격 |
| --- | --- |
| 에뮬레이터 이미지 | Pleos Connect v2.1.0 / Android API 34 / arm64-v8a |
| 장치 프로필 | `Pleos_Connect_v2_0` |
| 전체 화면 | 2560 × 1440 px, 160 dpi |
| 메모리 | RAM 4 GB |

공식 이미지와 장치 프로필은 [Pleos 개발 시작 안내](https://document.pleos.ai/docs/pleos-only/getting-started)를 따릅니다.

## APK 파일 위치

- [release APK 다운로드](https://github.com/chowonbhin/Pleos/raw/refs/heads/main/apk/HAM_release.apk): 현재 HAM 소스의 release 빌드. AI 연결에는 HTTPS 서버가 필요합니다.
- [현재 로컬 테스트 APK 다운로드](https://github.com/chowonbhin/Pleos/raw/refs/heads/main/apk/HAM_current.apk): 같은 소스의 debug 빌드. 위의 로컬 HTTP AI 서버 연결을 지원합니다.

두 APK는 같은 패키지(`app.memoryvault`)와 기존 개발 서명을 사용하므로 동시에 설치하지 않고 하나를 선택합니다. 공개된 앱 버전 값은 기존 소스와 동일한 `0.0.1-bootstrap`이며, 완성된 정식 서비스가 아닌 현재 시연용 구현입니다. 녹음·사진 등 사용자 기록과 AI 인증정보는 포함하지 않습니다. 새 기기에는 기존 에뮬레이터의 개인 데이터가 복제되지 않습니다. 자동 내비 분할과 공유 링크 수신은 지원하지 않습니다.

직접 빌드할 때의 출력 위치는 `app/build/outputs/apk/debug/app-debug.apk`, `app/build/outputs/apk/release/app-release-unsigned.apk`입니다. release 빌드 명령은 `./gradlew :app:assembleRelease`이며, 직접 만든 unsigned APK에는 자신의 로컬 키로 별도 서명이 필요합니다. 제출 시 파일명은 접수한 팀명을 붙여 `팀명_HAM_release.apk`로 사용합니다.
