# 기억 저장소 데모 v2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. This plan is a review draft, not execution approval.

**Goal:** 기존 사용자 데이터가 유입되지 않은 새 Pleos AVD에서 방문·사진·메모·공유 이미지와 로컬 음성 메모를 검증하고, 재현 가능한 APK·시연 자료를 준비한다.

**Architecture:** 단일 AAOS 앱에서 주행 Activity와 주차 Activity를 분리한다. Room에 로컬 데이터를 저장하고, 사진 매칭·주행 정책·음성 저장·공유본 생성은 순수 Kotlin으로 테스트한다. MediaStore·AAOS·Pleos STT는 인터페이스 뒤의 어댑터로 연결한다.

**Tech Stack:** Kotlin, Compose, Navigation Compose, Room/KSP2, coroutines, kotlinx.serialization, ExifInterface, AAOS Car APIs, Pleos SpeechToText 3.3.1, JUnit4, Android instrumentation/Compose UI tests.

**Spec:** [보완점 검토 및 설계안 v2](../specs/2026-11-13-memory-vault-demo-review-design-v2.md). 기존 Downloads 원본 두 파일은 변경하지 않는다.

**Status:** 2026-11-13 검토용. 아래 항목은 모두 미실행이며, 체크박스는 검증 증거가 확보된 뒤에만 체크한다. 신규 설계·계획 파일만 작성된 상태다.

## Global Constraints

- Git 원격은 `https://github.com/chowonbhin/Pleos.git`; 원격 push·제출·배포는 별도 명시 승인 후 수행한다.
- 사용자 기존 AVD `Pleos_Connect_2_1_API34` 및 이미지·앱·사진·DB·스냅샷을 변경하거나 복사하지 않는다.
- 새 AVD `Pleos_MemoryVault_Clean_API34`(이름 충돌 시 새 이름), 공식 Pleos API 34 ARM64, Cold boot, RAM 4 GB, 마이크 ON. 시스템 이미지 재사용은 출처·무결성 확인 후에만 허용한다.
- `applicationId=app.memoryvault`, `minSdk=28`, `targetSdk=34`, `compileSdk=35`; 시연 날짜 `2026-11-08`, 모든 도메인 시간대 `Asia/Seoul`.
- JDK 17 + Gradle 8.13 + AGP 8.13.2 + Build Tools 35.0.0 + Kotlin/Compose compiler 2.2.21 + KSP 2.2.21-2.0.4(KSP2)를 첫 검증 조합으로 사용한다. AndroidX 버전은 T1에서 호환 확인 후 정확히 고정한다.
- 서버·HYBRID STT·LLM·링크 공유·계정·실제 방문 감지·3D 맵은 기본 실행 범위 밖이다. Pleos STT는 ON_DEVICE를 명시한다.
- UNKNOWN은 편집·음성 시작·저장 이미지 공개·공유 시트를 잠근다. 차량 상태·녹음 진행 상태를 디스크에서 복원하지 않는다.
- 공유 DTO에 Note·좌표·주소·제외한 방문을 넣지 않는다. 원본 음성과 비밀값을 로그·소스·생성 APK에 넣지 않는다.
- 기존 문서는 수정하지 않는다. 구현 중 기존 `AGENTS.md`·`.gitignore`의 수정이 필요하면 이번 계획에 포함된 것으로 간주하지 말고 먼저 변경 필요성을 알린다.
- 구현은 순차 실행, 파일당 단일 작성자, 공용 Gradle·에뮬레이터·Git 조작은 메인 에이전트가 직렬 조정한다. 테스트 중 관련 입력과 소스를 동결한다.
- 기능 코드는 red→green→refactor TDD를 적용한다. 설정·생성 골격·시연 미디어는 불필요한 구현 모사 테스트 대신 실제 빌드·실행·메타데이터 확인을 사용한다.
- 계획 승인 후 일반 단계마다 재승인을 요구하지 않는다. 범위·외부 전송·파괴적 조작·공개 경계를 넘는 변경은 중단한다.

## Review Focus

1. 차량 서비스 초기화·끊김·D/정지·후진·OS overlay에서 편집이나 공유가 새지 않는가? → T2 정책/Activity 테스트, T8 새 AVD 실증.
2. 일부 사진 접근·권한 철회·재부여·손상 URI에서 앱이 유지되고 기존 해제 결정이 보존되는가? → T4 동기화 및 권한 테스트.
3. 음성 partial/중복 final·연속 탭·타임아웃·저장 실패가 빈 메모 또는 중복 저장을 만들지 않는가? → T5 세션·Repository 테스트.
4. 미리보기 이후 사진 접근이 사라지거나 출발하면 개인 기록·미완성 이미지가 공개되지 않는가? → T6 불변 스냅샷, T7 취소·공개 가드.
5. 새 AVD가 기존 userdata를 쓰거나 잘못된 serial에 설치·사진 주입하지 않는가? → T1 환경 증거, T8 최종 대상 검증.

## 단계, 의존성, 확인할 결과

| 단계 | 작업 | 의존성 | 사용자가 확인할 실행 결과 | 예상 순수 작업량 |
| --- | --- | --- | --- | --- |
| A 환경·잠금 | T1→T2 | 설계·계획 승인 | 새 AVD의 앱 실행, 차량 전환 시 잠금, 로컬 STT 지원 여부 | 3~5시간 |
| B 기록 | T3→T4→T5 | A | 3개 방문·샘플 사진·메모 저장, 재실행 유지, 음성 1경로 | 6~8시간 |
| C 공유 | T6→T7 | B | 카페·개인 메모가 빠진 PNG를 실제 열어서 확인 | 4~6시간 |
| D 제출 준비 | T8 | A~C | 검증된 APK·실제 녹화·README·검사 보고 | 4~6시간 |

총 17~25시간은 추정치이며 다운로드·장애 대응은 별도다. 11/14 밤까지 공유 경로를 확보하고, 11/15 정오 STT 결정, 18시 기능 동결, 21시 준비 완료를 목표로 한다. 원문 23:59 마감은 PRD에 적힌 값이며 공모전 사이트를 별도 검증한 값은 아니다. 늦어지면 P1·장식·3D를 먼저 제외하고 안전·보안·공유 누출 검증은 유지한다. F2를 텍스트만으로 대체하는 것은 사용자 승인 없이 P0 완료로 처리하지 않는다.

## 파일 구성과 공통 계약

아래 코드 경로의 루트는 `app/src/main/java/app/memoryvault/`이며 테스트는 동일 패키지를 `app/src/test/java/` 또는 `app/src/androidTest/java/` 아래에 둔다. 각 작업의 중괄호 파일 목록은 이 루트에서 각각의 실제 파일로 확장한다.

```text
app/src/main/AndroidManifest.xml
app/src/main/assets/seed_day.json
app/src/main/java/app/memoryvault/
  MemoryVaultApp.kt  AppContainer.kt  DemoConfig.kt
  MainActivity.kt  DrivingActivity.kt
  data/Entities.kt  Daos.kt  AppDatabase.kt  SeedLoader.kt  MemoryRepository.kt
  data/PhotoSource.kt  MediaStorePhotoSource.kt  ExifTime.kt  ShareSelectionStore.kt
  domain/Models.kt  PhotoMatcher.kt  Geo.kt  DrivePolicy.kt  VoiceMemo.kt  ShareDraft.kt
  platform/DriveStateSource.kt  AaosDriveStateSource.kt
  platform/SpeechInput.kt  PleosSpeechInput.kt  AndroidOnDeviceSpeechInput.kt
  platform/ShareImageLayout.kt  ShareImageRenderer.kt  ShareExporter.kt
  ui/DrivingScreen.kt  DayDraftScreen.kt  DayDraftViewModel.kt
  ui/ShareSelectScreen.kt  SharePreviewScreen.kt  ShareViewModel.kt
  ui/components/VisitCard.kt  PhotoTile.kt  NoteEditor.kt  StatusBanner.kt
app/src/debug/java/app/memoryvault/diagnostics/CapabilityProbe.kt
app/src/main/res/xml/share_file_paths.xml
app/src/main/res/xml/backup_rules.xml  data_extraction_rules.xml
app/schemas/
samples/p1.jpg ... p7.jpg  samples/README.md
tools/run_checks.sh  tools/pre_push_check.py  tools/tests/test_pre_push_check.py
.githooks/pre-push
docs/environment/pleos-memory-vault-v2.md
docs/verification/memory-vault-demo-v2.md
README.md
```

**도메인 타입의 기준 파일은 `domain/Models.kt`이다.** Room 엔티티와 UI는 이를 매핑해 사용하며 같은 도메인 타입을 별도로 재정의하지 않는다. 아래는 타입·경계 계약이며 완성 구현이 아니다.

```kotlin
enum class LinkState { CANDIDATE, CONFIRMED, REJECTED, UNLINKED }
enum class NoteKind { TYPED, VOICE }
enum class DriveState { UNKNOWN, DRIVING, PARKED }
enum class Route { Locked, Driving, DayDraft, ShareSelect, SharePreview }
data class DriveSnapshot(val state: DriveState, val activeRestrictions: Int)
data class Visit(val id: String, val date: String, val placeName: String,
    val lat: Double, val lng: Double, val arrivedAt: Long, val leftAt: Long)
data class Photo(val uri: String, val takenAt: Long?, val lat: Double?, val lng: Double?,
    val visitId: String?, val linkState: LinkState, val accessible: Boolean)
data class Note(val id: Long, val visitId: String, val text: String,
    val kind: NoteKind, val createdAt: Long, val voiceSessionId: String?)
data class VisitCard(val visit: Visit, val photos: List<Photo>, val notes: List<Note>)
data class DayDraft(val date: String, val cards: List<VisitCard>)
data class SeedDay(val date: String, val demoNow: Long, val visits: List<Visit>)
data class PhotoMeta(val uri: String, val takenAt: Long?, val lat: Double?, val lng: Double?)
data class PhotoScan(val photos: List<PhotoMeta>, val inaccessibleUris: Set<String>)
data class ShareSelection(val date: String, val includedVisitIds: Set<String>,
    val selectedPhotoUris: Set<String>, val captions: Map<String, String>)
data class ShareItem(val order: Int, val placeName: String,
    val photoUris: List<String>, val caption: String?)
data class ShareDraft(val date: String, val items: List<ShareItem>)
```

Room는 위 도메인 모델로 매핑한다. Note의 voiceSessionId에는 nullable unique index, 자식 visitId에는 FK·index를 둔다. 텍스트 입력 초안·공유 선택은 별도 Room 행에 저장한다. `PhotoScan.inaccessibleUris`는 알려진 URI를 재확인한 결과이며 **스캔에 없다는 이유만으로 DB 행을 삭제하지 않는다**.

## 실행·검증 공통 명령

T1에서 SDK·JDK·새 serial을 검증하여 다음 셸 변수에 바인딩한다. 아래 명령은 그 초기화 이후에 실행한다. `MV_SERIAL`을 비우거나 `adb -e`로 바꿔 실행하지 않는다.

```sh
MV_SDK="$HOME/Library/Android/sdk"
MV_ADB="$MV_SDK/platform-tools/adb"
MV_JDK="$HOME/Library/Java/JavaVirtualMachines/pleos-temurin-17.jdk/Contents/Home"
# MV_SERIAL은 adb devices 및 emu avd name을 대조해 얻은 새 AVD의 실제 serial.
"$MV_ADB" -s "$MV_SERIAL" emu avd name
env JAVA_HOME="$MV_JDK" ./gradlew testDebugUnitTest lintDebug assembleDebug
env JAVA_HOME="$MV_JDK" ANDROID_SERIAL="$MV_SERIAL" ./gradlew connectedDebugAndroidTest
"$MV_ADB" -s "$MV_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
"$MV_ADB" -s "$MV_SERIAL" shell am start -n app.memoryvault/.MainActivity
```

별도 SDK 루트를 선택했다면 환경 문서와 MV_SDK를 함께 변경한다. 공용 셸 환경·다른 프로젝트의 JAVA_HOME·Android Studio 전역 설정을 임의 변경하지 않는다. task 단위 테스트는 아래 클래스 필터를 사용하되, task 완료 시 해당 시점의 전체 JVM·lint·instrumentation 및 APK 빌드를 수행한다. T1은 제품 로직이 없는 설정 단계이므로 골격 빌드·설치·실행 확인으로 검증한다.

### Task 1 / MV-T1: 새 AVD와 실행 가능한 앱 골격·SDK 가능성 확인

**목적/의존성:** 설계·계획 승인 후 첫 작업. 기존 환경 보존과 이후 구현의 실제 실행 기반을 확보한다.

**소유 파일:** 신규 `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `gradle/wrapper/*`, `gradlew`, `gradlew.bat`, `app/build.gradle.kts`, manifest, `MemoryVaultApp.kt`, `AppContainer.kt`, `MainActivity.kt`, debug `CapabilityProbe.kt`, 새 environment 문서. AVD는 새 이름 경로만 소유한다.

**Interfaces:** `AppContainer`는 Application 수명의 서비스 조립점이다. `CapabilityProbe`는 제품 UI와 분리된 debug 진단이며 출력은 지원 여부·오류 유형만 포함한다. 진단으로 수집한 음성 텍스트는 로그에 남기지 않는다.

- [ ] 공식 설치 가이드와 package metadata로 ARM64/API 34/공식 이미지 버전을 확인한다. 불명확하면 기존 이미지를 덮어쓰지 않고 별도 루트에 설치한다.
- [ ] 새 AVD를 생성하고 Cold boot/4 GB/마이크를 설정한다. 기존 AVD의 userdata·snapshot·SD card를 복사하지 않는다.
- [ ] 새 AVD 이름↔serial↔userdata 경로를 대조한다. 최초 상태에서 `app.memoryvault` 미설치, 기존 사용자 사진 없음, 기존 계정 복원 없음의 증거를 기록한다.
- [ ] 고정 빌드 조합과 SDK 저장소를 설정한다. AndroidX 세부 버전을 안정 버전으로 고정하고 Room KSP 및 Compose 최소 빌드를 통과시킨다. bootstrap 생성 코드에는 TDD 예외를 적용한다.
- [ ] Car 서비스 연결/UX 제한 변화, Pleos STT ON_DEVICE initialize/한국어 final 결과/마이크/오프라인 사용을 진단한다. STT 권한, lifecycle 해제도 검증한다. 필요한 권한만 선언한다.
- [ ] 초기 VHAL 기어·속도 수신 여부를 확인할 표준 CarProperty API의 실제 지원·권한을 조사하고 환경 문서에 정확한 속성 ID·호출·관찰 결과를 고정한다. 서비스 연결 직후 기본 UX 제한 값만으로 주차라고 판정하지 않는다. 초기 수신 증거를 얻지 못하면 T2에서 UNKNOWN을 유지한다.
- [ ] `env JAVA_HOME="$MV_JDK" ./gradlew assembleDebug lintDebug`와 위 설치·실행 명령을 수행한다. 예상: BUILD SUCCESSFUL, 새 AVD에서 앱 표시. SDK 미지원은 통과로 기록하지 않는다.
- [ ] environment 문서에 도구 버전, SDK 의존성, 화면 크기, 새 AVD와 기존 환경의 분리, STT 성공·실패를 기록한다. 기어 P/D 및 속도 0/이동에서 실제 UX 제한 표를 남긴다.
- [ ] 변경·비밀정보 검사 후 소유 파일만 local commit한다. **확인 결과:** 새 AVD의 실행 화면과 지원 여부 표. 기존 환경 삭제나 원격 작업은 하지 않는다.

### Task 2 / MV-T2: 주행·미확인 상태 잠금과 Activity 전환

**목적/의존성:** T1 완료. 안전 잠금을 이후 모든 UI보다 먼저 고정한다. F1 담당.

**소유 파일:** `domain/Models.kt`의 Drive 타입, `domain/DrivePolicy.kt`, `platform/{DriveStateSource,AaosDriveStateSource}.kt`, `DrivingActivity.kt`, `MainActivity.kt`, `ui/DrivingScreen.kt`, manifest, `AppContainer.kt`. 테스트 `domain/DrivePolicyTest.kt`, androidTest `ui/DriveGateTest.kt`.

**Interfaces:** `DriveStateSource.snapshot: StateFlow<DriveSnapshot>`; `DrivePolicy.routeFor(snapshot: DriveSnapshot, requested: Route): Route`; `DrivePolicy.canEdit(snapshot): Boolean`; `DrivePolicy.canExport(snapshot): Boolean`. 어댑터는 최초 연결·현재 제한 확인 전 및 disconnect에서 UNKNOWN을 낸다. PARKED는 UX 허용 상태의 내부 이름이며 실제 기어 P의 증거라고 단정하지 않는다.

- [ ] 정책 실패 테스트를 작성한다: UNKNOWN은 모든 요청을 Locked로, DRIVING은 Driving으로; PARKED는 Locked/Driving에서 DayDraft로, 나머지는 유지. canEdit/canExport는 PARKED에서만 true.
- [ ] `./gradlew testDebugUnitTest --tests 'app.memoryvault.domain.DrivePolicyTest'`를 JDK 17로 실행해 의도한 실패를 확인한다.
- [ ] 정책과 어댑터를 구현한다. DO 필요 여부뿐 아니라 active restrictions를 전달한다. 초기 값을 PARKED로 임의 설정하지 않는다. listener 등록·해제를 대칭 처리한다.
- [ ] T1에서 확인한 초기 차량 속성 준비 상태를 정책 입력에 반영한다. 준비되지 않은 신호·읽기 실패·서비스 단절은 UNKNOWN이다. 실제 기어/속도와 UX 허용 상태가 충돌하면 편집을 차단하고, 추가 신호로 UX 제한을 완화하지 않는다. 이 조건의 adapter fake 테스트도 포함한다.
- [ ] 주차 Activity에 DO 메타데이터를 두지 않는다. 주행 Activity만 허용되는 제한된 경험으로 구현한다. 전환 시 키보드·dialog·편집 route를 닫고 뒤로가기로 편집 화면이 되살아나지 않게 한다.
- [ ] androidTest는 편집 화면에서 상태 전환, dialog/IME 중 전환, 재실행·뒤로가기·초기 UNKNOWN을 검사한다. 실제 새 AVD에서 P, D+속도0, D+이동, R, 서비스 연결 전후를 확인한다.
- [ ] 제한 해제가 실제 새 AVD에서 P와 부합하지 않으면 무조건 주차로 간주하지 않는다. 표준 CarProperty의 지원·권한을 확인해 주차 확인을 추가하거나 안전 잠금을 유지하고 blocker로 보고한다. 특권 System API·UX 제한 설정 변경으로 우회하지 않는다.
- [ ] task 전체 검사 후 local commit한다. **확인 결과:** Virtual Console 조작과 동시에 편집 화면이 사라지고 P에서 하루 초안 자리로 돌아오는 실행 데모.

### Task 3 / MV-T3: 방문·Room·하루 초안·재시작 보존

**목적/의존성:** T2 완료. F3/F4와 후속 사진·메모 저장 계약을 만든다.

**소유 파일:** `DemoConfig.kt`, `domain/Models.kt` 나머지 타입, `data/{Entities,Daos,AppDatabase,SeedLoader,MemoryRepository,PhotoSource,ShareSelectionStore}.kt`, `assets/seed_day.json`, `ui/{DayDraftScreen,DayDraftViewModel}.kt`, `ui/components/VisitCard.kt`, `AppContainer.kt`, Room schemas, backup XML·manifest. 테스트 `data/SeedLoaderTest.kt`, androidTest `data/{SeedPersistenceTest,MemoryRepositoryTest}.kt`.

**Interfaces:** `SeedLoader.parse(json: String): SeedDay`; `MemoryRepository.seedIfEmpty(seed: SeedDay)`(suspend); `observeDay(date: String): Flow<DayDraft>`; `getVisits(date: String): List<Visit>`(suspend); `PhotoSource.scan(knownUris: Set<String>): PhotoScan`(suspend); `ShareSelectionStore.observe(date): Flow<ShareSelection?>`, `save(selection)`(suspend). DAO의 동기 작업은 IO dispatcher에서 실행하고, 조회·변경 함수는 suspend/Flow로 노출한다.

- [ ] 실패 테스트: 순서 뒤집힌 시드가 v1/v2/v3로 정렬됨; leftAt<arrivedAt·중복 id·날짜 불일치 거부; 실제 assets와 fixture 동일; seed 두 번 후 count=3; 재시드가 사용자 행을 덮어쓰지 않음.
- [ ] JDK 17에서 `./gradlew testDebugUnitTest --tests 'app.memoryvault.data.SeedLoaderTest'`와 새 serial의 `connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.memoryvault.data.SeedPersistenceTest` 실패를 확인한다.
- [ ] JSON serialization, Room 트랜잭션·FK·voiceSessionId unique index, seed 버전 표식을 구현한다. visit별 photo/note를 combine한다. 보이는 사진은 접근 가능한 CANDIDATE/CONFIRMED다.
- [ ] 하루 초안에 장소·서울 시각·시연 데이터 표시와 빈 영역을 렌더링한다. 사진/메모가 0개여도 정상 화면이다. Android backup 및 device-transfer에서 DB·개인 기록을 제외한다.
- [ ] 전체 task 검사, 앱 force-stop 후 재실행으로 카드 3개와 사용자 상태 보존을 확인한다. 검증은 새 serial에만 수행한다.
- [ ] local commit. **확인 결과:** 시간순 3개 카드가 보이고 재시작해도 중복 없는 앱.

### Task 4 / MV-T4: 사진 후보·권한·동기화·확정/해제

**목적/의존성:** T3 완료. F5/F6 담당. 실제 샘플을 이 단계 전에 준비한다.

**소유 파일:** `data/{PhotoSource,MediaStorePhotoSource,ExifTime,MemoryRepository,Daos}.kt`, `domain/{Geo,PhotoMatcher}.kt`, `ui/DayDraftScreen.kt`, `ui/components/{PhotoTile,StatusBanner}.kt`, manifest, `samples/*`. 테스트 `domain/PhotoMatcherTest.kt`, `data/ExifTimeTest.kt`, androidTest `data/PhotoSyncTest.kt`, `ui/PhotoPermissionTest.kt`.

**Interfaces:** `ExifTime.parse(dateTime: String?, offset: String?, zone: ZoneId = DEMO_ZONE): Long?`; `distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double`; `PhotoMatcher.match(photo: PhotoMeta, visits: List<Visit>): String?`; repository `syncPhotos(date: String)`, `setPhotoState(uri: String, state: LinkState)`(suspend). setPhotoState는 UI에서 CONFIRMED/REJECTED만 허용한다.

- [ ] 실패 테스트: offset 없음은 서울 14:05; UTC 05:05+00:00은 동일 시각; year0/잘못된 날짜/offset 실패는 null. 300 m 경계·체류 양끝 포함, 시각/좌표 없음·NaN·잘못된 범위·멀리 떨어진 사진은 미연결, 여러 후보는 정해진 정렬로 결정.
- [ ] JVM 대상 테스트를 실행해 실패 확인 후 순수 파싱·하버사인·매칭을 최소 구현한다.
- [ ] Room 실패 테스트: matched→CANDIDATE, rejected→rescan 후 REJECTED 유지, confirmed 유지, UNLINKED→새 위치 권한 후 후보 재평가, 삭제/권한 상실 URI→비표시·결정 보존, 다른 사진 읽기 실패가 나머지를 막지 않음.
- [ ] 실패 확인 후 MediaStore 스캔·권한 분기·IO 처리·오류 격리·재동기화를 구현한다. API 29+에서 GPS 동의가 있을 때만 setRequireOriginal을 호출한다. API 34의 전체/선택/거부 접근을 구분하고 onResume에 현재 권한을 다시 확인한다.
- [ ] 샘플 7장을 합성 또는 사용 가능한 자료로 준비한다. 설계안의 EXIF를 검증한 뒤 새 serial에만 주입하고 media scan한다. p1→식당, p2/p3/p4/p6→해변, p5→카페, p7→미연결을 확인한다.
- [ ] 사진 타일에 후보/확정·확정/해제 동작을 둔다. 권한 요청·재선택은 주차에서만 가능하다. 재시작·재스캔 후 p6 해제를 유지한다.
- [ ] task 전체 검사 후 local commit. **확인 결과:** 샘플 후보가 실제 표시되고 p6 제거·권한 거부·일부 선택 상황에서도 앱이 동작한다.

### Task 5 / MV-T5: 직접 메모와 로컬 음성 메모

**목적/의존성:** T4 및 T1의 실제 STT 조사. F2/F7 담당.

**소유 파일:** `domain/VoiceMemo.kt`, `platform/{SpeechInput,PleosSpeechInput,AndroidOnDeviceSpeechInput}.kt`, `data/{Daos,MemoryRepository}.kt`, `ui/{DrivingScreen,DayDraftScreen,DayDraftViewModel}.kt`, `ui/components/NoteEditor.kt`, `AppContainer.kt`, manifest. 테스트 `domain/VoiceMemoTest.kt`, `platform/SpeechSessionTest.kt`, androidTest `data/NotePersistenceTest.kt`.

**Interfaces:** `SpeechInput.listenOnce(): SttResult`(suspend), `cancel()`, `close()`; `SttResult = Text(value: String) | Error(code: String) | Cancelled`; `targetVisit(visits: List<Visit>, startedAt: Long): Visit?`; `VoiceMemoOutcome = Saved(visitId: String) | NothingHeard | NoVisit | Failed | Cancelled | Busy`. Repository `addNote(visitId,text,kind,now,voiceSessionId: String? = null): Boolean`, `updateTypedNote(noteId: Long,text: String): Boolean`(suspend). `VoiceMemoUseCase.record(sessionId: String, startedAt: Long, input: SpeechInput): VoiceMemoOutcome`(suspend), `cancel()`, `state: StateFlow<VoiceUiState>`; VoiceUiState는 Idle/Listening/Processing/Result(outcome)이다.

- [ ] 실패 테스트: 14:58→v2, 15:30→v3, 11:00→NoVisit; 입력 `"  애들이 바다에서 한참 놀았네\n"` 그대로 저장; blank/error/cancel은 0행; addNote=false이면 Failed; 같은 sessionId final 두 번은 1행; 녹음 후 시각이 바뀌어도 시작 방문 유지.
- [ ] JVM 및 NotePersistenceTest 실패를 확인한다. 직접 메모 수정은 TYPED만 가능하고 다른 방문·VOICE 원문은 수정되지 않는 테스트도 포함한다.
- [ ] Repository 저장·편집과 순수 use case를 구현한다. 유효하지 않은 방문이면 마이크를 열지 않는다. 동시 호출은 Busy로 반환한다.
- [ ] Pleos `Mode.ON_DEVICE`, 한국어, initialize→listener→request→final→stop→removeListener/release 수명을 구현한다. partial은 저장하지 않고 complete final만 세션당 1회 처리한다. 15초 녹음+5초 결과 대기 제한, coroutine 취소, 늦은 콜백 무시를 구현한다.
- [ ] Android 대체는 API31+ 로컬 엔진 존재·한국어 지원 확인 후에만 사용한다. 일반 recognizer나 서버로 자동 대체하지 않는다. 두 경로 불가면 미충족 상태를 보고하고 나머지 작업을 계속한다.
- [ ] 직접 메모 입력 초안·확정 메모를 보존한다. 주행 시 받아쓰기 텍스트를 숨기고 녹음 표시/취소만 허용한다. 주차 전환 중 세션은 같은 방문에 완료되고 background/UNKNOWN에서는 취소한다.
- [ ] 실제 새 AVD에서 음성→P→해변 원문 확인, 마이크 거부·무음·취소·연속 탭·오프라인·재시작을 검증한다. task 전체 검사 후 local commit. **확인 결과:** 손으로 쓴 메모와 실제 발화가 각각 보존되는 앱; STT 지원 실패는 명확히 표시.

### Task 6 / MV-T6: 공유 선택·불변 공유본·미리보기

**목적/의존성:** T5 완료 또는 F2 blocker를 명시한 상태로 로컬 흐름 진행. F8/F9 담당.

**소유 파일:** `domain/ShareDraft.kt`, `data/ShareSelectionStore.kt`, `ui/{ShareViewModel,ShareSelectScreen,SharePreviewScreen,DayDraftScreen}.kt`, `MainActivity.kt`. 테스트 `domain/ShareDraftBuilderTest.kt`, androidTest `ui/ShareSelectionTest.kt`.

**Interfaces:** `ShareDraftBuilder.build(selection: ShareSelection, day: DayDraft): ShareDraft?`; `ShareViewModel.selection: StateFlow<ShareSelection>`, `draft: StateFlow<ShareDraft?>`; `toggleVisit(id: String)`, `togglePhoto(uri: String)`, `setCaption(visitId: String,text: String)`, `openPreview(): ShareDraft?`. preview는 새 List/Map으로 복사한 불변 스냅샷을 소유한다.

- [ ] 실패 테스트: 카페 제외→식당·해변과 order 1/2만; REJECTED/접근불가 사진은 선택 집합에 있어도 제외; 제외 방문의 사진·설명도 제외; 개인 메모·좌표 필드 없음; caption trim/blank null; 0방문 null; 원본 선택 수정 후 기존 스냅샷 불변.
- [ ] nullable 테스트는 아래처럼 검증하고 컴파일 오류와 의도한 실패를 구분한다.

```kotlin
val result = requireNotNull(ShareDraftBuilder.build(selection, day))
assertEquals(listOf("대부도 칼국수집", "방아머리해변"), result.items.map { it.placeName })
assertEquals(listOf(1, 2), result.items.map { it.order })
assertFalse(result.toString().contains("애들이"))
```

- [ ] `./gradlew testDebugUnitTest --tests 'app.memoryvault.domain.ShareDraftBuilderTest'` 실패 확인 후 builder·보존 저장소·ViewModel을 구현한다.
- [ ] UI는 방문 스위치·사진 체크·공유 설명·미리보기로 구성한다. 최초 기본 선택은 접근 가능한 후보/확정 전부지만 이후 재진입·재스캔에서 사용자가 제외한 사진을 자동 재선택하지 않는다.
- [ ] 주행→주차 시 하루 초안으로 이동하고 공유 화면을 다시 열면 이전 선택을 복원한다. force-stop 후 복원도 검사한다. 미리보기에는 ShareDraft 외 원본 도메인 접근을 넘기지 않는다.
- [ ] task 전체 검사 후 local commit. **확인 결과:** 카페·p3·개인 메모가 없는 실제 미리보기와 재실행 후 선택 유지.

### Task 7 / MV-T7: 이미지 생성·갤러리 저장·안전한 공유 시트

**목적/의존성:** T6 완료. F10 담당.

**소유 파일:** `platform/{ShareImageLayout,ShareImageRenderer,ShareExporter}.kt`, `ui/SharePreviewScreen.kt`, res/xml `share_file_paths.xml`, manifest. 테스트 `platform/ShareImageLayoutTest.kt`, androidTest `platform/{ShareExportTest,ExportSafetyTest}.kt`.

**Interfaces:** `ShareImageLayout.lines(draft: ShareDraft): List<String>`; `ShareImageRenderer.render(draft: ShareDraft,widthPx: Int = 1080): Bitmap`(suspend); `ShareExporter.export(draft: ShareDraft): ExportResult`(suspend); `ExportResult = Saved(uri: Uri) | Blocked | Failed(code: String)`; `shareIntent(uri: Uri): Intent`. Exporter는 DriveStateSource를 주입받아 시작·공개 직전·공유 호출 직전 가드를 수행한다.

- [ ] 실패 테스트: 날짜/번호/설명 순서 일치; 줄바꿈 한글·emoji·긴 설명; 폭1080·높이12000 제한 초과는 잘림 대신 명시 오류. 사진 EXIF 회전 적용, 원본 EXIF를 새 PNG에 복사하지 않음.
- [ ] instrumentation 실패 테스트: URI 읽기 실패/저장 공간 오류→공개 이미지 없음; pending 작성 중 출발→취소·이번 임시 행 정리; 미리보기 이후 권한 철회→저장 중단; 저장 성공 후 chooser 없음→파일은 남고 안내; 공유 grant read flag 존재.
- [ ] 실패를 확인하고 다운샘플·캔버스·텍스트 배치·새 PNG 인코딩을 구현한다. 한 번의 내보내기에서 공유 대상 URI 외 사진이나 원본 메모를 참조하지 않는다.
- [ ] API29+에서는 `Pictures/기억저장소/`, MIME image/png, IS_PENDING=1→완료 후0을 사용한다. 실패 정리는 생성한 URI만 대상으로 한다. API28은 public Pictures 저장 권한 처리와 FileProvider를 분기한다. API28 실기 검증 미수행이면 보고서에 명시한다.
- [ ] 현재 상태가 PARKED일 때만 공유 시트를 연다. 출발 이후 완료된 이미지가 있다면 공유 시트 자동 실행을 보류한다. OS 공유 시트가 이미 열린 상태에서 출발하는 실제 동작도 새 AVD에서 확인한다.
- [ ] 실제 PNG를 열어 카페·제외 사진·개인/음성 메모·좌표가 없고 한글·이미지가 잘리지 않았는지 확인한다. 이미지 메타데이터도 검사한다.
- [ ] task 전체 검사 후 local commit. **확인 결과:** 새 AVD에서 저장된 PNG와 공유 시트, 실패 시 미완성 결과가 보이지 않는 실행 데모.

### Task 8 / MV-T8: 통합 검증·push 검사·APK와 시연 자료

**목적/의존성:** T1~T7. 통합 소스 동결 후 최종 검증과 검토 가능한 제출물을 만든다. 미충족 F2가 남으면 'P0 완료'로 결론 내리지 않는다.

**소유 파일:** 신규 `tools/run_checks.sh`, `tools/pre_push_check.py`, `tools/tests/test_pre_push_check.py`, `.githooks/pre-push`, `README.md`, `docs/verification/memory-vault-demo-v2.md`. 제품 코드 수정이 필요하면 해당 T 작업의 소유자에게 반환하고 검증 범위를 다시 동결한다.

**Interfaces:** `tools/run_checks.sh`는 새 AVD 이름/serial 검증 후 JVM·lint·APK·instrumentation을 직렬 실행하고 한 단계라도 실패하면 nonzero로 종료한다. `python3 tools/pre_push_check.py --local`은 외부 전송 없이 검사하고 nonzero면 실패다. `--pre-push`는 Git pre-push stdin의 모든 ref를 받아 원격/브랜치/대상 commit tree·이력을 검사한다. 숨김 값은 출력하지 않는다.

- [ ] push 검사 테스트를 먼저 작성한다: 새 브랜치의 과거 커밋에 가짜 비밀값이 있고 HEAD에서 지웠어도 차단; 여러 ref 중 한 ref 문제도 차단; 도구 부재·검사 오류·미검사 바이너리면 차단; 알려진 키 값은 로그에 나오지 않음. 임시 fixture repo만 사용하고 실제 키를 테스트 데이터로 쓰지 않는다.
- [ ] `python3 -m unittest discover -s tools/tests -v`의 의도한 실패를 확인한 후 검증된 로컬 secret scanner 호출·대상 이력 선택·출력 redaction을 구현한다. scanner 버전을 고정한다. 자체 정규식만으로 모든 키 형식을 탐지했다고 주장하지 않는다.
- [ ] hook은 기존 hooksPath·pre-push가 있으면 덮어쓰지 않는다. 이 새 저장소에 충돌이 없을 때만 로컬 hook 경로를 설정한다. hook은 보조 장치이며 우회 불가능한 보안 경계가 아니다. 실제 push 승인을 대신하지 않는다.
- [ ] 소스·staged/working changes·전송될 전체 이력·추적 파일과 생성 APK의 DEX/resources/manifest, 로그·첨부 이미지/압축파일을 검사한다. 알려진 실제 키가 있다면 로컬에서만 대조하며 값은 보고하지 않는다. APK 내부 검사와 수동 첨부 검토가 끝나지 않으면 배포 준비 완료로 판정하지 않는다.
- [ ] 최종 `tools/run_checks.sh`와 `python3 -m unittest discover -s tools/tests -v`, `python3 tools/pre_push_check.py --local`, `git diff --check`를 실행하고 exit status·commit SHA·소스 변경 유무·APK SHA-256을 기록한다. 검사 후 변경은 영향 범위를 재검사한다.
- [ ] 아래 통합 매트릭스를 새 AVD에서 실행한다. fake 기반 단위 테스트 결과와 실제 플랫폼 검증 결과를 별도 열로 기록한다.
- [ ] README에 환경·새 AVD 선택·샘플 주입·7단계 시연·사용한 STT 모드·기능 한계·시드임을 적는다. 원본 문서의 작성자 이름·개인 계정·자격증명은 공개 README에 복사하지 않는다.
- [ ] 약 2분 실제 화면 녹화와 PNG·APK를 준비한다. 사진 장면은 '휴대폰 사진을 가정한 시연 샘플'처럼 실제 구현과 구분한다. 영상을 찍지 않았으면 영상 제작 완료라고 하지 않는다.
- [ ] 실행 방식에 따른 독립 최종 검토를 받는다. 미해결 발견 사항은 누락하지 않고 보고한다. 검사한 정확한 변경만 local commit하며 push는 하지 않는다.
- [ ] **확인 결과:** 설치 가능한 APK, 실제 시연 영상, PNG, README, 테스트/보안 검사 보고와 남은 제한 목록. 모든 실행 Ruling을 이유·오판 비용과 함께 최종 보고한다.

## 통합 검증 매트릭스

| ID | 검증 | 통과 기준 | 소유 |
| --- | --- | --- | --- |
| A01 | 새 환경 분리 | 기존 AVD 무변경, 새로운 userdata, 앱 설치 전 기존 데이터 없음 | T1/T8 |
| A02 | 부팅·끊김·주행 | UNKNOWN/DRIVING에서 편집·키보드·공유 진입 불가 | T2 |
| A03 | 주차 복귀 | P에서 하루 초안, 기존 공유 선택 보존 | T2/T6 |
| A04 | 시드 중복 | 재시작 후 방문 3개·순서·서울 시각 동일 | T3 |
| A05 | 사진 매칭 | p1 식당, p2~p4/p6 해변, p5 카페, p7 미연결 | T4 |
| A06 | 사용자 결정 | p6 해제 후 재스캔·재실행에도 비표시 | T4 |
| A07 | 권한 변화 | 전체/일부/거부/철회/재부여에서 crash 없음·재평가 정상 | T4 |
| A08 | 직접 메모 | 추가·수정·재실행 보존, 음성 원문 불변 | T5 |
| A09 | 실제 음성 | 로컬 STT final 1회만 v2 저장, offline 확인 | T5 |
| A10 | 음성 실패 | 무음·권한·중복·취소·저장 실패에서 빈/중복 메모 없음 | T5 |
| A11 | 공유 제외 | 카페·p3·p6·개인/음성 메모·주소·좌표가 결과에 없음 | T6/T7 |
| A12 | 내보내기 경쟁 | 저장 중 출발/권한철회에 미완성 이미지·자동 공유 없음 | T7 |
| A13 | 파일 품질 | 선택 사진 방향·한글 정상, 새 PNG에 GPS/원본 EXIF 없음 | T7 |
| A14 | 재현성 | 새 serial에 APK 재설치 후 시드→시연 7단계 재현 | T8 |
| A15 | 비밀정보 | 소스/이력/산출물/로그 검사 완료, 의심 또는 미검사 대상이면 차단 | T8 |

API34만 실제 실행한 경우 API28~33 호환은 lint·분기 검증과 구분하여 제한으로 남긴다. hook만 설치한 경우 수동 push나 다른 도구의 우회까지 방지한다고 말하지 않는다.

## 요구사항 추적 및 자체 검토

| 근거 | 담당 |
| --- | --- |
| PRD p.1 목표·비목표·안티목표 | 설계 §5~7, T2/T5/T6/T7 |
| PRD p.2 시연 1~7 | T5→T3→T4→T5→T6→T6→T7, 최종 녹화 T8 |
| PRD p.3 F1 | T2 |
| PRD p.3 F2 | T1 가능성 확인, T5 실제 구현 |
| PRD p.3 F3/F4 | T3 |
| PRD p.3 F5/F6 | T4 |
| PRD p.3 F7 | T5 |
| PRD p.3 F8/F9 | T6 |
| PRD p.3 F10 | T7 |
| PRD p.3 F11~F15 | 최소 구분 표기만 P0 내 보완, 나머지는 기본 범위 밖·별도 승인 |
| PRD p.4 모델·로컬 DB | T3와 공유 DTO T6 |
| PRD p.5~6 일정·검증 8항목 | 단계 일정·A02~A11·T8 |
| PRD p.7 실제 실행 환경·시연 한계 | T1/T8 README |
| 사용자 새 에뮬레이터·기존 내용 제외 | T1/A01 |
| 사용자 매 push 키 점검 | T8/A15 + 기존 프로젝트 AGENTS 지침 |

자체 검토 기준: 원문 요구의 task 매핑, 서로 같은 타입/메서드 이름 사용, Review Focus별 테스트, 각 단계 실행 결과·검사 책임, 새 AVD 식별과 키 보호, 원본 보존. 독립 검토 완료로 표현하지 않는다.

## 실행 방식과 중단 경계

사용자가 아직 실행 방식을 선택하지 않았다. 원하면 **메인 에이전트 순차 실행 + 마지막 독립 검토**를 권장한다. 이 계획은 인터페이스가 촘촘히 연결된 한 앱이고 납기 시간이 짧아 조정 비용을 줄일 수 있다. 작업별 독립 검토를 원하면 **새 구현자 1명→새 검토자 1명→다음 작업** 순서의 subagent-driven 방식을 선택할 수 있다. 병렬 구현은 하지 않는다.

어느 방식이든 사용자 지정 Astra high/Sol max/standard service를 임의 변경하지 않는다. 현재 subagent 도구에는 priority만 표시되므로 standard 지원 확인 또는 사용자의 예외 승인 전에 subagent를 실행하지 않는다. 이 제약 때문에 이번 계획의 독립 builder 검토는 미실시이며 자체 검토만 제공한다.

실행 시작 시 선택한 Superpowers 실행 스킬의 임시 workspace·ledger·brief·report를 생성한다. 실행 중 유지하고, 깨끗한 최종 검토와 수정 통합 후 그 계획의 임시 자료만 정리한다. 이 설계·계획·검증 보고와 요청된 APK/영상은 보존한다. `Ruling: 결정 — 이유 — 틀렸을 때 비용`을 기록한다. 현재는 아직 승인된 계획을 실행하지 않아 실행 Ruling이 없다.

외부 push·배포·제출, 기존 데이터 삭제, F2 요구 축소, cloud 음성 전송·키 사용·추가 비용이 필요한 경우에는 그 결정이 필요한 구체적 결과를 제시하고 승인을 받는다. 단순 구현 선택·일반 단계 통과는 반복 승인 사유가 아니다.
