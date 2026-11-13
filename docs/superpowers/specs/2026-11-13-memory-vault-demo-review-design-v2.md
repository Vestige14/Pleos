# 기억 저장소 데모 — 보완점 검토 및 설계안 v2

작성일: 2026-11-13, Asia/Seoul. 상태: **검토용 제안, 구현 승인 전**.

이 문서는 제공된 PRD와 구현 계획을 대체·수정하지 않는 별도 제안이다. 사용자가 확정한 사항, 공식 문서에서 확인한 사실, 작성자의 보완 제안을 구분한다. 앱 구현·SDK 설치·AVD 생성·커밋·push·제출은 아직 수행하지 않았다.

## 1. 요청과 검토 범위

사용자가 확정한 사항:

- 기존 PRD와 Markdown의 기억 저장소 데모를 기반으로 구현할 계획이다.
- 이 폴더는 `https://github.com/chowonbhin/Pleos.git`을 사용한다.
- 매 push 전에 Pleos Playground API·SDK 키를 포함한 비밀정보를 검사한다.
- 공식 개발자 가이드에 따라 **새 Pleos 에뮬레이터**를 사용한다. 기존 에뮬레이터의 앱·사진·DB 등 내용이 새 환경에 들어오면 안 된다.
- 원본 Markdown·PDF 및 기존 파일을 수정하지 않고 새 파일로 결과를 제공한다.

원문:

- `/Users/wonbin/Downloads/2026-11-13-memory-vault-demo.md` — Task 1~15 구현 계획.
- `/Users/wonbin/Downloads/기억 저장소 데모 PRD.pdf` — 총 7쪽. 아래 쪽수는 PDF 실제 페이지와 인쇄 페이지가 같다.
- 원문 파일명의 한글은 파일시스템에서 자모 분해 형태로 저장되어 있다.

이번에 확인한 것은 원문 전체, 공식 SDK·Android 문서, 로컬 개발 도구·AVD 설정이다. 실제 Pleos 서비스 연결, 한국어 음성 인식, 차량 상태 수신, APK 빌드는 아직 검증하지 않았다. 대시보드 자격증명은 열람하지 않았다.

## 2. 결론

원안의 **Kotlin·Compose 단일 AAOS 앱 + Room + 순수 Kotlin 판단 로직 + 플랫폼 어댑터** 구조는 유지할 가치가 있다. 공유 데이터와 개인 기록을 분리하는 방향도 적절하다. 다만 그대로 실행하면 최신 SDK와의 불일치, 주행 상태 미확인, STT 외부 전송, 사진 권한 예외 때문에 시연이 실패할 수 있다.

권장 접근은 로컬 데모 흐름을 유지하면서 환경·주행 잠금·STT 가능 여부를 먼저 검증하는 것이다. 서버·계정·실제 방문 감지·3D 맵을 함께 늘리는 대안은 제출 전 검증 범위를 크게 늘리므로 이번 기본 계획에서 제외한다. UI를 먼저 완성하고 SDK를 마지막에 붙이는 대안은 SDK 실패를 늦게 발견하므로 권장하지 않는다. 이 선택은 작성자 추천이며 사용자 승인 전이다.

## 3. 보완점과 원문 대비 변경

| ID | 원문 위치·문제 | 새 제안·검증 기준 |
| --- | --- | --- |
| R01 필수 | PRD p.4, Markdown Global Constraints: minSdk 26 | 현재 공식 요구는 API 28 이상이다. **minSdk 28**로 수정 제안한다. 실제 검증 기기는 API 34다. [S1] |
| R02 필수 | PRD p.4, F13/Task 14: Pleos LLM SDK | Emulator 2.1.0 공식 릴리스에서 LLM SDK 지원 종료가 명시돼 있다. **F13은 이번 계획에서 보류**하고 원문 그대로 보존한다. 다른 LLM 서비스 추가는 별도 범위·전송·비용 결정이다. [S2] |
| R03 필수 | 로컬 처리 원칙과 STT 모드 미지정이 충돌 | Pleos STT 기본값은 서버 우선 HYBRID다. **ON_DEVICE 명시**를 권장한다. 초기 한국어 인식 검증을 통과해야 F2 실현 가능으로 판정한다. [S3] |
| R04 필수 | Task 11은 RECORD_AUDIO만 기재 | Pleos SDK용 `pleos.car.permission.STT_SERVICE`를 추가한다. Android 대체 인식기는 실제 사용하는 경로에 맞춰 RECORD_AUDIO와 RecognitionService 조회 선언을 검토한다. [S4, S5] |
| R05 필수 | STT 연결을 마지막 날에 처음 수행 | 첫 실행 단계에서 ON_DEVICE 초기화·한국어 발화·마이크·오프라인 동작을 확인한다. 실패하면 원인과 지원 여부를 기록하고 나머지 로컬 흐름은 계속한다. |
| R06 필수 | DRIVING/PARKED 2상태, 주차 브레이크 대체 | 초기화 중·서비스 끊김을 **UNKNOWN**으로 추가하고 편집·내보내기를 잠근다. 주차 브레이크 하나만으로 PARKED를 추정하지 않는다. UX 제한의 활성 비트도 반영한다. [S6] |
| R07 필수 | Activity 전체를 distractionOptimized로 지정 | 메타데이터만으로 주행 안전이 보장되지 않는다. 주행 전용 Activity와 주차 편집 Activity 분리를 제안하며, 새 AVD에서 OS 차단과 전환을 먼저 검증한다. 전용 Activity의 Pleos 허용 여부도 확인한다. [S6, S7] |
| R08 필수 | Task 4의 사진 전체 스캔, 권한 성공 전제 | Android 14의 전체·일부·거부 접근을 처리한다. 권한 철회, 삭제된 URI, 손상 EXIF를 개별 사진 실패로 격리한다. 위치가 없으면 null이며 0,0으로 대체하지 않는다. [S8, S9] |
| R09 필수 | 사진 INSERT IGNORE만 수행 | 사용자 확정·해제는 유지하되, 이전에 위치 접근이 없던 UNLINKED 사진은 새 권한 부여 후 재평가한다. 사진 접근 가능 여부와 사용자의 연결 결정은 별도 필드로 관리한다. |
| R10 필수 | 공백·오류 외 음성 세션 규칙 없음 | 중복 탭·중간 결과·중복 최종 콜백·취소·타임아웃·DB 저장 실패를 구분한다. 인식 시작 시 방문을 고정하고 최종 결과를 1회만 저장한다. [S10] |
| R11 필수 | preview 객체와 실제 export 사이 상태 변경 | 미리보기는 불변 ShareDraft 스냅샷을 사용한다. 내보내기 시작·파일 공개·공유 시트 진입 시 주차 상태와 사진 접근 권한을 다시 확인한다. |
| R12 필수 | MediaStore 내보내기에 실패 처리 없음 | API 29+에서는 IS_PENDING으로 작성 완료 후 공개한다. 실패하면 이번 작업이 만든 미완성 행만 제거한다. API 28은 FileProvider와 실제 필요한 저장 권한을 분기한다. [S8] |
| R13 필수 | 재시작 검증이 메모·사진 일부에 한정 | 일반 앱 재시작 시 메모·확정·해제·공유 선택·입력 초안을 복원한다. 차량 상태와 녹음 진행 상태는 복원하지 않고 다시 확인한다. DB 자동 백업·복원도 차단한다. |
| R14 필수 | Task 6 build()가 nullable인데 일부 테스트에서 바로 .items 접근 | non-null을 먼저 검증하는 테스트로 고친 새 계획을 사용한다. Review Focus의 주행 게이트 Task 10 참조도 실제 Task 9와 맞지 않는다. 원본은 수정하지 않는다. |
| R15 필수 | 역할이 인식됨과 확정됨을 혼용할 가능성 | 방문에 '시연 데이터', 사진에 '후보/확정', 메모에 '직접/음성'을 최소 표기로 둔다. 방문 시드가 실시간 감지 결과인 것처럼 표현하지 않는다. 전체 출처 배지 확장은 P1이다. |
| R16 필수 | minSdk 26과 API 29+ 저장 API를 무조건 사용 | minSdk를 28로 올려도 API 29 분기는 필요하다. lint의 NewApi 오류를 막지 말고 런타임 가드·API별 저장 경로를 구현한다. 실제 기기 검증 범위는 API 34로 명시한다. |
| R17 필수 | 키를 Git에서 제외하면 APK도 안전하다는 오해 가능 | 소스·Git 이력 외 생성 BuildConfig, manifest/resources, DEX, APK/압축 파일·로그까지 점검한다. ON_DEVICE 데모는 서버 자격증명을 요구하지 않는 경로부터 검증한다. |
| R18 제안 | 사진·음성 샘플을 마지막에 준비 | 첫 환경 검증 직후 합성 또는 사용 권한이 확인된 샘플 7장을 준비한다. 공유 픽셀 자체의 얼굴·영수증·주소는 EXIF 제거로 숨겨지지 않으므로 최종 미리보기를 직접 검사한다. |

## 4. 새 Pleos 에뮬레이터 설계

### 확인된 로컬 상태

- Apple Silicon arm64, Android Studio 설치됨.
- 기존 AVD: `Pleos_Connect_2_1_API34`; Cold boot 및 마이크 입력 설정 존재.
- 설치된 이미지 경로: `system-images/android-34/pleos_car_emulator/arm64-v8a/`.
- 이미지 source.properties에서 API 34·arm64를 확인했다. AVD 이름만으로 이미지 릴리스가 공식 2.1.0임을 확정하지 않는다.
- Android Emulator 37.2.13, SDK Platforms 34/35, Build Tools 35.0.0 존재.
- JDK 17.0.21 존재. Android Studio 내장 JBR와 기본 Java는 25 계열이므로 Gradle JVM을 명시적으로 17로 지정할 예정이다. Java 25에서 Gradle 8.x가 지원된다고 가정하지 않는다. [S11]
- `adb`, `emulator`, `sdkmanager`, `avdmanager`, `gitleaks`는 현재 PATH에서 발견되지 않았다. Android 도구는 SDK의 절대 경로로 실행할 수 있는지 먼저 확인한다.

### 생성 방식

1. 공식 가이드 [S1]의 Mac ARM64 저장소·API 34·Pleos Connect 2.1.0 프로파일을 기준으로 설치 이미지의 package metadata와 출처를 확인한다.
2. 새 AVD 이름은 `Pleos_MemoryVault_Clean_API34`로 한다. 같은 이름이 있으면 덮어쓰지 않고 접미사 `_02` 등 미사용 이름을 선택해 환경 문서에 기록한다.
3. **AVD 복제 기능을 사용하지 않는다.** 새 userdata·SD card·cache를 만들고 기존 `.avd`, `userdata-qemu.img`, snapshot, 사진, DB를 복사하지 않는다.
4. 시스템 이미지는 OS 템플릿이다. 검증된 공식 이미지만 재사용할 수 있으며, 기존 AVD의 사용자 데이터와는 분리한다. 원본 이미지가 수정됐거나 출처 확인이 안 되면 별도 SDK 루트에 공식 이미지를 새로 설치한다.
5. 기존 설치를 삭제하라는 가이드의 안내를 이 프로젝트의 기존 환경 삭제 승인으로 해석하지 않는다. 설치 경로가 충돌하면 별도 SDK 루트를 사용하고 기존 환경은 보존한다.
6. 공식 설정에 따라 RAM 4 GB, Cold boot, 마이크 입력 ON을 적용한다. 한국어와 Asia/Seoul을 확인하고, 앱의 날짜 처리는 기기 시간대와 별도로 Asia/Seoul로 고정한다.
7. 첫 부팅에 기존 계정·백업을 복원하지 않는다. 공식 OS에 포함된 기본 앱은 그대로 둘 수 있으나 기존 사용자가 설치한 앱과 개인 데이터는 가져오지 않는다.
8. 새 AVD의 정확한 serial을 확인하고 모든 `adb` 명령 및 instrumented test는 그 serial에만 실행한다. 기존 AVD에 install, clear, push, wipe 명령을 보내지 않는다.
9. Virtual Console은 새 serial에만 포트 전달한다. 기본 포트 충돌 시 새 로컬 포트를 택하고 기존 포워딩을 제거하지 않는다. [S12]

### 환경 완료 기준

- 최초 앱 설치 전 `app.memoryvault` 패키지가 없고 기존 사용자 사진·앱 데이터가 없음을 확인한다.
- 시스템 지문, 이미지 revision, AVD 이름·새 데이터 경로·serial·화면 크기를 기록한다.
- 시연 사진 주입 전/후 목록을 기록해 샘플 이외의 사진이 유입되지 않았음을 확인한다.
- Virtual Console의 기어·속도 변화, UX restrictions 수신, 마이크 입력을 실제 관찰한다.
- Cold boot는 사용자 데이터를 지우지 않는다. 이후 검증에서 재부팅만으로 초기 상태라고 주장하지 않는다.

## 5. 범위와 데이터 계약

### 유지할 P0

F1 주행 잠금, F2 음성 메모 1경로, F3 시드 방문, F4 하루 초안, F5 사진 후보 연결, F6 확정·해제, F7 직접 메모 추가·수정, F8 공유 선택, F9 공유본·미리보기, F10 이미지 저장·공유 시트.

PRD p.1~4의 비목표를 유지한다: 실제 방문 감지, 서버·링크 공유, 계정·동기화, 휴대폰 앱, 원본 음성 저장, 동승자 수집, 자동 기억 연결은 구현하지 않는다. F11 배지 확장·F12 사진 이동은 P0와 최종 검증 후에만 별도 선택한다. F13·3D 맵은 기본 승인 범위에서 제외하는 제안이다.

### 고정 시연 자료

- 패키지: `app.memoryvault`; 날짜: `2026-11-08`; 시간대: `Asia/Seoul`.
- v1 대부도 칼국수집: 12:00~13:10, (37.2830, 126.5810).
- v2 방아머리해변: 13:30~14:50, (37.2846, 126.5779).
- v3 바다 앞 카페: 15:10~16:00, (37.2790, 126.5850).
- 음성 시연 기준 시각: 14:58. 이후 카페 방문까지 포함한 고정 하루 자료를 사용하는 **재생용 시드**임을 문서에 명시한다. 실시간 수집 화면으로 표현하지 않는다.
- 사진 p1 식당 12:25, p2/p3/p4 해변 13:45/14:10/14:30, p5 카페 15:20, p6 영수증 14:12 해변, p7 시각·GPS 없음. 전부 2026-11-08 기준이며 p1 위치는 (37.2831,126.5811), 해변·카페 사진은 해당 방문 좌표를 쓴다.

### 도메인 규칙

- 방문은 도착 시각, 동률이면 id 순으로 정렬한다. seedId가 같은 데이터는 다시 넣어도 중복·덮어쓰기가 없다.
- 사진은 촬영 시각이 체류 구간 양끝을 포함하고 거리 300 m 이하인 방문에만 후보 연결한다. 여러 방문이 맞으면 도착 시각·id 정렬상 첫 방문을 선택한다.
- EXIF offset이 있으면 사용하고, 없으면 서울 시각으로 읽는다. 잘못된 날짜·위도/경도·NaN·시각/위치 누락은 미연결이다. 거부된 GPS 권한을 위치 0,0으로 바꾸지 않는다.
- 사용자 결정 `CANDIDATE/CONFIRMED/REJECTED/UNLINKED`와 접근 가능 여부를 분리한다. 재스캔으로 CONFIRMED/REJECTED를 덮어쓰지 않는다. 사라진 사진의 사용자 결정도 보존한다.
- 메모는 공백만 있는 입력을 저장하지 않고 유효한 입력은 공백·개행까지 그대로 저장한다. 사용자가 작성한 메모만 수정 가능하고 음성 원문은 불변이다.
- 음성 대상 방문은 녹음 시작 시각 이하로 도착한 방문 중 가장 최근 방문이다. 같은 녹음 sessionId는 DB에서도 한 번만 저장한다. 저장 실패를 Saved로 반환하지 않는다.
- ShareDraft는 날짜·정렬된 항목 목록만 가지며, 항목은 이름·순서·선택 사진 URI·공유 설명만 가진다. Visit/Note 객체, 주소·좌표·원본 메모를 참조하지 않는다.
- 공유 기본값은 원안처럼 모든 방문·현재 접근 가능한 후보/확정 사진 선택이다. 후보는 확정으로 자동 바꾸지 않고 표기를 유지한다. 제외 방문의 사진·설명은 builder에서 재차 거른다. 포함 방문 0개는 미리보기 불가다.
- 공유 설명은 trim하고 공백이면 null이다. 개인 메모를 자동 복사하지 않는다. 공유 선택은 날짜별 저장하며 주행 전환·일반 앱 재시작 후 복원한다.
- 내보내기 결과는 새 PNG로 인코딩하며 원본 EXIF/GPS를 복사하지 않는다. 선택 사진의 픽셀 내용까지 개인정보가 없다고 보장하지 않는다.

## 6. 화면·주행·음성 정책 제안

| 상태/화면 | 동작 | 금지·실패 처리 |
| --- | --- | --- |
| UNKNOWN | '차량 상태 확인 중'만 표시, 재연결 | 편집·음성 시작·내보내기 모두 잠금 |
| DRIVING | 큰 '한마디 남기기' 버튼, 녹음 상태·취소, 간단한 결과 | 사진·키보드·음성 받아쓰기 텍스트·공유 화면 표시 금지 |
| PARKED / 하루 초안 | 시간순 방문 카드, 사진 후보/확정/해제, 메모 추가·수정 | 권한 요청은 주차 상태에서만 수행 |
| PARKED / 공유 선택 | 방문 포함, 사진 선택, 공유 설명 입력 | 0개 방문이면 다음 단계 비활성 |
| PARKED / 미리보기 | ShareDraft만 렌더링, 저장 후 공유 | 출발·상태 상실 시 즉시 닫고 작업 가드 적용 |

주행 전용 `DrivingActivity`와 주차 전용 `MainActivity`를 분리한다. 주차 Activity에는 distractionOptimized를 달지 않는다. 주행 Activity만 제한을 준수하도록 설계하되, 메타데이터를 시스템 제한 우회 수단으로 사용하지 않는다. 새 AVD가 해당 경험을 허용하는지 Task 1~2에서 확인한다. 앱 내 경로·뒤로가기·재실행·최근 앱·IME·dialog 모두 같은 정책을 적용한다. OS가 소유한 공유 시트는 앱이 임의로 닫을 수 있다고 가정하지 않고 실제 동작을 검증한다.

UX 제한은 물리적 기어 상태와 같은 값이 아니며, VHAL의 초기 상태가 도착하기 전 기본적으로 제한 없음처럼 보일 수도 있다. 따라서 서비스 연결만으로 초기 PARKED를 확정하지 않는다. Task 1에서 실제 차량 상태 초기 수신을 확인할 수 있는 표준 속성·권한을 조사하고, 준비 확인이 불가능하면 UNKNOWN을 유지한다. 기어·속도 정보로 추가 잠금을 걸 수 있어도 UX 제한을 완화하지는 않는다. 특권 `CarDrivingStateManager` 접근이나 시스템 UX 정책 변경을 전제로 삼지 않는다. [S17]

음성은 `Idle → Listening → Processing → Saved/Error` 상태를 갖는다. 한 세션은 최대 15초, 최종 결과 대기는 추가 최대 5초로 제한한다(작성자 제안). partial 결과는 저장하지 않는다. DRIVING↔PARKED 변경만으로 현재 세션을 중단하지 않고 동일 visitId에 저장하며 어느 화면에서도 녹음 상태를 표시한다. background·사용자 취소·차량 상태 UNKNOWN에서는 중단하고 늦게 도착한 콜백을 무시한다. permission dialog나 모델 설치는 주차 시에만 진행한다.

Pleos ON_DEVICE가 불가하면 API 31+의 `isOnDeviceRecognitionAvailable`로 Android on-device 인식기를 확인한다. 일반 `createSpeechRecognizer` 또는 `EXTRA_PREFER_OFFLINE`만으로 로컬 처리를 보장하지 않는다. 두 로컬 경로 모두 불가능하면 F2 미충족으로 명시하고, 주차 텍스트 입력 대체 여부를 사용자에게 결정받는다. 서버 모드·키 사용·유료 서비스는 자동 대체하지 않는다. [S3, S5]

## 7. 기술 및 검증 경계

- 단일 Android app 모듈, Kotlin/Compose/Navigation/Room(KSP)/coroutines/serialization/ExifInterface.
- `minSdk=28`, `targetSdk=34`, `compileSdk=35`를 초기 데모 제안으로 사용한다. targetSdk 34는 API 34 에뮬레이터 검증용 선택이며 스토어 배포 적합성을 주장하지 않는다.
- 초기 빌드 조합: JDK 17, Gradle 8.13, AGP 8.13.2, Build Tools 35.0.0, Kotlin 및 Compose compiler plugin 2.2.21, KSP 2.2.21-2.0.4(KSP2). 공식 호환 표·릴리스에 근거한 시작 후보이며 실제 전체 조합 성공은 Task 1에서 검증한다. [S13, S14]
- Pleos STT `ai.pleos.playground:SpeechToText:3.3.1`, 공식 Maven 저장소만 사용한다. Vehicle SDK는 AAOS가 필요한 상태를 제공하지 못하고 지원 API를 확인한 경우에만 추가한다. 공식 예제 버전은 2.3.1이다. [S15, S16]
- AndroidX 등 세부 의존성은 Task 1에서 compileSdk 35와 호환되는 안정 버전으로 선택·정확히 고정하고 dependency report를 남긴다. 동적 버전이나 무검증 최신 버전 일괄 업그레이드는 금지한다.
- 프로덕션 코드의 Room·MediaStore·Car·STT 어댑터와 순수 판단 로직을 분리한다. 장치 없는 JVM 테스트 통과가 실제 STT/차량 연동 성공을 의미하지 않는다.
- 이미지 내보내기는 원본 다운샘플링·EXIF 회전·한글 줄바꿈을 적용한다. 1080 px 너비, 최대 12,000 px 높이 및 메모리 예산을 검사하고 초과 시 선택 축소 안내를 표시한다. 조용히 사진·글을 잘라내지 않는다.
- 앱 로컬 DB·초안은 backup/restore 제외, 민감한 원문은 로그 제외. 영구 개인 데이터 삭제 기능은 이번 범위에 추가하지 않는다.

## 8. 일정과 승인할 결정

문서상 사전 제출 목표는 2026-11-15 21:00 KST, 최종 마감은 23:59다. 외부 공모전 마감 자체를 독립 검증한 것은 아니다. 현재 11/13 저녁이므로 기존 Day 1 전체가 남아 있다고 가정하지 않는다.

권장 작업량은 순차 구현·검증 약 17~25시간과 장애 대응 여유다. 다운로드, SDK 지원 여부, 사진 준비에 따라 달라지며 마감 보장이 아니다. 11/14 밤까지 F1·F3~F10 통합 경로, 11/15 정오까지 F2 가능 여부 확정, 18시 기능 동결, 21시 제출 준비를 목표로 한다. 안전·비밀정보·공유 누출 검증은 일정 축소 대상이 아니다.

승인 시 확인할 주요 제안은 minSdk 28, ON_DEVICE 음성, 상태 미확인 시 잠금, Activity 분리, F13·3D 보류다. 이 문서 작성은 이 변경들의 승인을 의미하지 않는다. F2 축소·외부 전송·push·배포·메일 발송·기존 데이터 삭제는 자동 진행하지 않는다.

독립 subagent 검토는 수행하지 않았다. 계획 작성 중 자체 교차검토를 수행하며, 구현 실행 방식을 선택한 후 적용되는 독립 검토를 별도로 수행한다. 현재 subagent 도구는 priority만 명시해 사용자 지침의 standard service를 보장할 수 없으므로, 임의 대체 실행하지 않는다.

## 9. 근거

공식 문서 확인일: 2026-11-13. 아래 출처는 기술 사실의 근거이며, 데모 범위와 새 환경 보존 조건은 사용자·PRD가 정한다.

- [S1 — Pleos Emulator 설치 및 실행](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)
- [S2 — Pleos Emulator 2.1.0 릴리스](https://document.pleos.ai/en/docs/pleos-only/release-note/emulator-release-note)
- [S3 — Pleos STT 모드](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/intro)
- [S4 — STT 권한](https://document.pleos.ai/en/api-reference/connect-sdk-pleos/SpeechToText/declaring-permissions)
- [S5 — Android SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer)
- [S6 — CarUxRestrictions](https://developer.android.com/reference/android/car/drivingstate/CarUxRestrictions)
- [S7 — AAOS 주차 앱과 Activity 제한](https://developer.android.com/training/cars/parked/automotive-os)
- [S8 — Android 공유 미디어와 EXIF](https://developer.android.com/training/data-storage/shared/media)
- [S9 — Android 14 일부 사진 접근](https://developer.android.com/about/versions/14/changes/partial-photo-video-access)
- [S10 — Pleos STT 결과 콜백](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/result-listeners)
- [S11 — Gradle Java 호환 표](https://docs.gradle.org/current/userguide/compatibility.html)
- [S12 — Pleos Virtual Console](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/use-vehicle-emulator)
- [S13 — AGP 8.13 호환 표](https://developer.android.com/build/releases/agp-8-13-0-release-notes)
- [S14 — Kotlin 2.2.21 지원 KSP 릴리스](https://github.com/google/ksp/releases/tag/2.2.21-2.0.4)
- [S15 — STT 의존성](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/importing-sdk)
- [S16 — Pleos 앱 시작 및 Vehicle 의존성](https://document.pleos.ai/docs/pleos-only/getting-started)
- [S17 — AAOS 주행 상태·초기 UX 제한 규칙](https://source.android.com/docs/automotive/driver_distraction/car_uxr)

원본 SHA-256:

- Markdown: `8c4268fb99818bbe0e95afe1f2753ba715547a7935ac8867a45b23095738b791`
- PDF: `82f991fda47944bf2249aa33e5196f495026214fecdea93ab28c6d07ff5aa3b1`
