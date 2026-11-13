# 기억 저장소 데모 Implementation Plan v2

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pleos Connect 에뮬레이터에서 '방문 → 하루 초안 → 사진·메모 수정 → 공유 선택 → 이미지 내보내기'와 주행 중 음성 메모 1경로가 동작하는 데모 APK를 11/15 21시까지 만든다.

**Architecture:** 단일 AAOS 앱(Kotlin + Compose). 모든 기록은 Room에 두고, 판단 로직(사진 매칭, 공유본 생성, 화면 게이트, 음성 메모 대상 방문)은 Android 의존성이 없는 순수 Kotlin 객체로 분리해 JVM 단위 테스트로 검증한다. Android/Pleos에 닿는 부분(MediaStore, UX 제한, STT)은 인터페이스 뒤에 두어 교체 가능하게 한다. **위험이 큰 두 가지(STT, 주행 상태)는 Day 1에 스파이크로 먼저 찔러 본다.**

**Tech Stack:** Kotlin, Jetpack Compose, Navigation Compose, Room(KSP), kotlinx.serialization, androidx.exifinterface, AAOS `android.car`(CarUxRestrictionsManager), Pleos SpeechToText SDK(ON_DEVICE 전용), JUnit4, kotlinx-coroutines-test, Room in-memory(androidTest)

**Spec:** 기억 저장소 데모 PRD — https://claude.ai/artifact/666zbNcBeQYNqZb8arR6A7

**개발자:** 1인. v1(`2026-11-13-memory-vault-demo.md`)과 리뷰(`2026-11-13-memory-vault-demo-review-design-v2.md`)는 그대로 두고, 이 파일이 실행 기준이다.

## v1 대비 변경 (리뷰 반영 결과)

| 리뷰 | 결정 | 반영 위치 |
| --- | --- | --- |
| R01 minSdk 26 → 28 | **받되 29로** 올린다. 공식 요구(28 이상)를 충족하면서 MediaStore API 29+ 분기가 통째로 사라진다. 검증 기기는 API 34 | Global Constraints, Task 1 |
| R02 LLM SDK | F13 **보류**. 원래 P1이라 잃는 것이 없다. 지원 종료 공지는 확인하지 못했으므로 "보류" 사유는 일정 |  Task 15 삭제 |
| R03 STT 기본값 HYBRID | **받음.** `Mode.ON_DEVICE` 명시. 서버 모드는 쓰지 않는다 | Global Constraints, Task 2 |
| R04 STT 권한 | **받음.** `pleos.car.permission.STT_SERVICE` 추가 | Task 2 |
| R05 STT를 마지막 날에 처음 | **받음.** Day 1 Task 2 스파이크로 당긴다 | Task 2 |
| R06 UNKNOWN 상태 | **축소 반영.** enum 값 추가 + 그 상태에서 잠금. 초기 수신 확인은 Task 2 실험 결과로 판단 | Task 9 |
| R07 Activity 분리 | **실험 후 결정.** Day 1에 30분 타임박스로 확인하고, 안 되면 단일 Activity + RouteGate 유지 | Task 2 |
| R08 사진 권한·EXIF 예외 | **축소 반영.** 사진 1장 실패를 격리(try/catch). 권한 철회 처리는 제외 | Task 5 |
| R09 UNLINKED 고착 | **받음.** 기계가 정한 상태는 재스캔 시 재계산, 사용자가 정한 상태만 보존 | Task 5 |
| R10 음성 세션 규칙 | **축소 반영.** 재탭 무시, 시작 시 대상 고정, 최종 1회 저장, 15초 타임아웃. DB 세션 중복 방지는 제외 | Task 11 |
| R11 스냅샷·재확인 | **축소 반영.** 내보내기 직전 주차 상태 재확인만 | Task 8 |
| R12 IS_PENDING | **받음.** minSdk 29라 분기 없이 IS_PENDING 경로 하나 | Task 8 |
| R13 재시작 복원 | **축소 반영.** `allowBackup=false`만. 공유 선택 복원은 제외 | Task 1 |
| R14 계획 오류 2건 | **받음.** nullable `build()` 테스트 수정, Review Focus의 Task 번호 수정 | Task 6, Review Focus |
| R15 출처 표기 | **축소 반영.** 방문에 '시연 데이터' 표기만. 배지 확장은 P1 | Task 4 |
| R16 API 28 분기 | **불필요해짐.** minSdk 29 | — |
| R17 APK 비밀정보 | **받음.** ON_DEVICE라 키 자체가 없다. 제출 전 점검만 | Task 12 |
| R18 샘플 사진 선행 | **받음.** Day 1 끝에 준비 | Task 4 Step 6 |
| 리뷰의 AVD 절차 | **축소 반영.** 새 AVD + 모든 adb를 `-s <serial>`로 고정. 지문·revision 기록은 제외 | Task 1 |
| 리뷰의 STT 3.3.1 | **미확정.** 공식 페이지는 2.1.3.2. Task 2에서 실제 저장소 확인 | Task 2 |

## Global Constraints

- `minSdk 29`, `targetSdk 34`, `compileSdk 35`, Gradle 8.0 이상, Kotlin. minSdk는 공식 요구가 API 28 이상(차량 사이버보안 요건)이라 그 위에서 고른 값이다.
- 테스트 기기는 **이 프로젝트 전용으로 새로 만든** Pleos Connect Emulator AVD. Boot option = **cold boot**(quick boot면 VHAL 사용 불가), 마이크 입력 ON. 모든 `adb`·instrumented test는 `-s <새 serial>`로 고정해 기존 AVD를 건드리지 않는다.
- 패키지명 `app.memoryvault`. 매니페스트에 `android:allowBackup="false"`.
- **STT는 `Mode.ON_DEVICE` 전용.** 서버 모드·`registerApp`·client ID/secret을 쓰지 않는다. 따라서 앱과 저장소에 Pleos 자격증명이 존재하지 않는다. ON_DEVICE가 안 되면 Android 온디바이스 인식기 → 주차 중 텍스트 입력 순으로 내려가고, 서버 모드로는 넘어가지 않는다.
- 시간대는 항상 `Asia/Seoul` 고정(`DEMO_ZONE`). 에뮬레이터 기본 시간대를 쓰지 않는다.
- 네트워크 호출 없음. 매니페스트에 INTERNET 권한을 선언하지 않는다(ON_DEVICE 전용의 부수 효과이자 검증 수단).
- 공유 이미지에는 **포함한 방문의 이름·순서·선택한 사진·공유용 설명만** 들어간다. Note, 좌표, 주소, 제외한 방문은 공유본 타입에 필드조차 없다.
- 차량 상태가 DRIVING이거나 UNKNOWN이면 편집·공유·내보내기를 잠근다.
- AI는 사용자가 말하지 않은 감정·사실·동행자를 추가하지 않는다. 음성 원문은 불변이다.
- 매니페스트에는 실제로 쓰는 권한만 선언한다.

## Review Focus

1. **EXIF 시각에 시간대가 없음**: `DateTimeOriginal`은 오프셋 없는 현지 시각이다. 기기 시간대로 읽으면 9시간 밀려 어떤 사진도 매칭되지 않는다 → Task 3 `noOffset_readsAsSeoulTime`.
2. **사진 GPS가 지워져서 읽힘**: API 29+에서 `ACCESS_MEDIA_LOCATION`과 `MediaStore.setRequireOriginal()` 없이 열면 위치가 제거된 사본이 온다 → Task 5 Step 6 수동 확인.
3. **권한 부여 전 스캔으로 상태가 고착됨**: 위치 권한을 주기 전 스캔하면 모든 사진이 UNLINKED로 박힌다 → Task 5 `unlinkedPhoto_isReevaluatedOnRescan`.
4. **편집 중 차가 출발함 / 상태 미확인**: 공유 선택 중 주행이 되면 즉시 주행 화면으로 가야 하고, 상태를 모르는 동안에도 편집이 열리면 안 된다 → Task 9 `drivingOrUnknown_overridesEveryRoute`.
5. **STT가 빈 문자열·중간 결과·오류를 돌려줌**: 빈 메모 저장, 중간 결과 저장, 중복 저장 모두 막아야 한다 → Task 11 `partialResult_isNotSaved`, `blankText_savesNothing`, `sttError_savesNothing`.

---

## File Structure

v1과 같되 다음이 바뀐다.

```
app/src/main/java/app/memoryvault/
  domain/RouteGate.kt        # DriveState에 UNKNOWN 추가
  platform/SpeechInput.kt    # PleosSpeechInput(ON_DEVICE 고정), AndroidSpeechInput
  platform/ShareImage.kt     # IS_PENDING 경로 하나 (API 28 분기 없음)
spike/                       # Day 1 스파이크 기록(코드는 본 앱에 남기지 않음)
  stt-findings.md  drive-state-findings.md
```

공용 테스트 헬퍼 `Fixtures.kt`(test·androidTest 양쪽):

```kotlin
fun t(hm: String): Long = OffsetDateTime.parse("2026-11-08T$hm:00+09:00").toInstant().toEpochMilli()
val restaurant = Visit("v1", "2026-11-08", "대부도 칼국수집", 37.2830, 126.5810, t("12:00"), t("13:10"))
val beach      = Visit("v2", "2026-11-08", "방아머리해변",   37.2846, 126.5779, t("13:30"), t("14:50"))
val cafe       = Visit("v3", "2026-11-08", "바다 앞 카페",   37.2790, 126.5850, t("15:10"), t("16:00"))
val day = listOf(restaurant, beach, cafe)
```

---

## Day 1 · 11/13(금) 저녁 — 관문: STT 가능 여부 판정 + 초안 화면에 시드 방문 3곳

### Task 1: 전용 AVD + 프로젝트 골격

**Files:** 새 프로젝트, `DemoConfig.kt`, `AndroidManifest.xml`

**Interfaces:**
- Produces: `val DEMO_ZONE: ZoneId = ZoneId.of("Asia/Seoul")`, `const val DEMO_DATE = "2026-11-08"`

- [ ] **Step 1: 전용 AVD 생성** — Device Manager에서 Automotive form factor, Pleos Connect v2.1.0 이미지로 새 AVD `Pleos_MemoryVault_API34`를 만든다. 기존 AVD를 복제하지 않는다. Boot option = cold boot, 마이크 입력 ON, RAM 4 GB.
  Run: 부팅 후 `adb devices`
  Expected: 새 serial이 보인다. 이 serial을 `spike/drive-state-findings.md` 맨 위에 적고, 이후 모든 adb 명령에 `-s`로 붙인다.
- [ ] **Step 2: 프로젝트 생성** — minSdk 29 / targetSdk 34 / compileSdk 35. Room(KSP)·kotlinx.serialization·exifinterface·navigation-compose 의존성, `android { useLibrary("android.car") }`, 매니페스트에 `<uses-feature android:name="android.hardware.type.automotive" android:required="true"/>`와 `android:allowBackup="false"`.
  Run: `./gradlew assembleDebug && adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk`
  Expected: 빈 Compose 화면이 뜬다. 에뮬레이터 Virtual Console에서 기어를 바꿀 수 있다.
- [ ] **Step 3: Commit** — `git commit -m "chore: project skeleton for Pleos demo"`

### Task 2: 스파이크 — STT와 주행 상태 (버리는 코드)

이 태스크는 **스파이크**다. 테스트를 쓰지 않고, 결과만 기록한 뒤 코드는 버린다. 알아낸 것은 Task 9·11에서 본 구현으로 다시 쓴다. **합계 2시간 타임박스.**

- [ ] **Step 1: STT 의존성 확인 (10분)** — `maven("https://nexus-playground.pleos.ai/repository/maven-releases/")`를 저장소에 추가하고 `ai.pleos.playground:SpeechToText`의 실제 최신 버전을 확인한다(공식 문서는 2.1.3.2, 리뷰는 3.3.1을 주장). 받아지는 버전을 `spike/stt-findings.md`에 적는다.
- [ ] **Step 2: ON_DEVICE 한국어 인식 (60분 타임박스)** — 버튼 하나뿐인 임시 Activity에서:
  ```kotlin
  val stt = SpeechToText(context, Mode.ON_DEVICE)   // 서버 모드 금지. registerApp 호출하지 않음
  stt.initialize()
  stt.addListener(object : ResultListener {
      override fun onUpdated(text: String, completed: Boolean) { /* 로그: text, completed */ }
      override fun onError(errorType: ErrorType) { /* 로그: errorType */ }
      override fun onReady() {}
      /* 나머지 콜백은 로그만 */
  })
  stt.request()
  ```
  매니페스트에 `pleos.car.permission.STT_SERVICE`와 `RECORD_AUDIO`를 넣는다.
  Run: 호스트 마이크로 "애들이 바다에서 한참 놀았네" 발화
  Expected: `onUpdated(..., completed = true)`에 한국어 문장이 온다.
  `spike/stt-findings.md`에 적을 것: 인식된 문자열, `completed=false`로 몇 번 오는지, 호스트 네트워크를 끈 상태에서도 되는지, 에러면 `ErrorType`(`PERMISSION_DENIED`면 권한, `ON_DEVICE_ENGINE_ERROR`면 엔진 미탑재, `NOT_SUPPORTED_API`면 모드 미지원).
- [ ] **Step 3: 판정 (STT)** — 위가 되면 F2는 Pleos ON_DEVICE로 간다. 안 되면 `SpeechRecognizer.isOnDeviceRecognitionAvailable()`(API 31+)로 Android 온디바이스 인식기를 확인한다. 둘 다 안 되면 **F2를 주차 중 텍스트 입력으로 확정**하고 PRD 결정 표를 고친다. 어느 경우든 서버 모드로 넘어가지 않는다. 판정을 findings에 한 줄로 적는다.
- [ ] **Step 4: 주행 상태 수신 (30분 타임박스)** — `Car.createCar(context)` → `CarUxRestrictionsManager`를 받아 `isRequiresDistractionOptimization`을 로그로 찍는다.
  Run: Virtual Console에서 기어 P ↔ D 전환
  Expected: 값이 바뀐다.
  findings에 적을 것: 앱 시작 직후 첫 값이 언제 오는지(즉시 오는지, 콜백을 기다려야 하는지), 기어 외에 속도도 영향을 주는지.
- [ ] **Step 5: Activity 분리 실험 (30분 타임박스)** — `distractionOptimized=true` Activity 하나와 메타데이터 없는 Activity 하나를 만들고, 주행 중에 후자를 열어 본다.
  Expected 두 갈래 중 하나: (a) OS가 차단 화면을 띄우고, 앱이 주행 Activity로 전환할 수 있다 → **Activity 분리 채택**. (b) 차단 화면에서 빠져나오지 못하거나 전환이 어색하다 → **단일 Activity + RouteGate 유지**(v1 방식).
  판정을 findings에 적는다. Task 9는 이 판정을 따른다.
- [ ] **Step 6: 스파이크 코드 제거** — 임시 Activity와 로그를 지운다. findings 두 파일만 남긴다.
- [ ] **Step 7: Commit** — `git commit -m "docs: spike findings for stt and drive state"`

### Task 3: 시드 방문 → Room (F3)

v1 Task 1과 동일하다. `SeedLoaderTest`(`parsesVisitsInTimeOrder`, `leftBeforeArrived_isRejected`, `realSeedFile_matchesFixtures`)와 `VisitDaoTest.seedTwice_keepsThreeVisits`를 그대로 쓴다.

- [ ] **Step 1: `seed_day.json` 작성**

```json
{ "date": "2026-11-08", "demoNow": "2026-11-08T14:58:00+09:00",
  "visits": [
    {"id":"v1","placeName":"대부도 칼국수집","lat":37.2830,"lng":126.5810,"arrivedAt":"2026-11-08T12:00:00+09:00","leftAt":"2026-11-08T13:10:00+09:00"},
    {"id":"v2","placeName":"방아머리해변","lat":37.2846,"lng":126.5779,"arrivedAt":"2026-11-08T13:30:00+09:00","leftAt":"2026-11-08T14:50:00+09:00"},
    {"id":"v3","placeName":"바다 앞 카페","lat":37.2790,"lng":126.5850,"arrivedAt":"2026-11-08T15:10:00+09:00","leftAt":"2026-11-08T16:00:00+09:00"} ] }
```

- [ ] **Step 2: 실패하는 테스트** (v1 Task 1 Step 3의 테스트 그대로)
- [ ] **Step 3: 실패 확인** — `./gradlew testDebugUnitTest --tests "app.memoryvault.data.SeedLoaderTest"` → FAIL
- [ ] **Step 4: 구현** — 엔티티(`Visit`, `Photo`, `Note`, `LinkState`, `NoteKind`), `AppDatabase`, `VisitDao`, `SeedLoader`. JSON은 kotlinx.serialization으로 읽는다(org.json은 JVM 단위 테스트에서 "not mocked" 예외).
  `LinkState`는 **사용자 결정과 기계 결정을 구분**한다: `CANDIDATE`(기계), `UNLINKED`(기계), `CONFIRMED`(사용자), `REJECTED`(사용자). `val LinkState.isUserDecided get() = this == CONFIRMED || this == REJECTED`.
- [ ] **Step 5: 통과 확인** — unit PASS, `connectedDebugAndroidTest`(`-s <serial>`) PASS
- [ ] **Step 6: Commit** — `git commit -m "feat: seed visits into Room"`

### Task 4: MemoryRepository + 하루 초안 화면 (F4, F15 최소)

v1 Task 2와 같고, 카드에 '시연 데이터' 표기가 추가된다.

**Interfaces:**
- Produces: `VisitCard`, `DayDraft`, `MemoryRepository(db, photoSource)`: `seedIfEmpty(seed)`, `observeDay(date): Flow<DayDraft>`; `fun interface PhotoSource { suspend fun scan(): List<PhotoMeta> }`, `data class PhotoMeta(uri, takenAt, lat, lng)`

- [ ] **Step 1: 실패하는 테스트** — `observeDay_returnsCardsInTimeOrder` (androidTest, fake PhotoSource는 빈 목록)
- [ ] **Step 2: 실패 확인** → FAIL
- [ ] **Step 3: 구현** — visit·photo·note 세 Flow를 `combine`해 visitId로 묶는다.
- [ ] **Step 4: 통과 확인** → PASS
- [ ] **Step 5: 화면** — 시작 시 `assets/seed_day.json`으로 `seedIfEmpty`. 카드마다 장소명과 `HH:mm–HH:mm`(DEMO_ZONE). 화면 상단에 "시연 데이터" 한 줄을 둔다(실시간 감지로 오해하지 않도록).
  Run: `-s <serial>`로 설치·실행
  Expected: 세 카드가 시간순으로 보이고, 재시작해도 3장이다.
- [ ] **Step 6: 시연 사진 준비 (R18)** — 사진 7장에 EXIF를 넣는다. 사진은 직접 찍었거나 사용 권한이 확인된 것만 쓴다.

| 파일 | 내용 | DateTimeOriginal | 위치 | 기대 결과 |
| --- | --- | --- | --- | --- |
| p1 | 칼국수 | 2026:11:08 12:25:00 | 37.2831, 126.5811 | 칼국수집 후보 |
| p2·p3·p4 | 해변·아이 | 13:45 / 14:10 / 14:30 | 37.2846, 126.5779 | 해변 후보 |
| p5 | 카페 음료 | 15:20 | 37.2790, 126.5850 | 카페 후보 |
| p6 | 영수증 | 14:12 | 37.2846, 126.5779 | 해변 후보 → 시연에서 해제 |
| p7 | 아무 사진 | (없음, `exiftool -all=`) | 없음 | 어디에도 안 붙음 |

```bash
exiftool -DateTimeOriginal="2026:11:08 13:45:00" -GPSLatitude=37.2846 -GPSLatitudeRef=N -GPSLongitude=126.5779 -GPSLongitudeRef=E samples/p2.jpg
adb -s <serial> push samples/. /sdcard/Pictures/
adb -s <serial> shell content call --method scan_volume --uri content://media --arg external_primary
```

- [ ] **Step 7: Commit** — `git commit -m "feat: day draft screen with seeded visits"`

**Day 1 관문:** STT 가능 여부 판정 완료 + 초안 화면에 시드 방문 3곳.

---

## Day 2 · 11/14(토) — 관문: P0 공유 흐름 완성(F1, F3~F10)

### Task 5: EXIF 시각 파싱 + 사진 후보 매칭 (F5 로직)

v1 Task 3과 동일하다. `ExifTimeTest`(`noOffset_readsAsSeoulTime`, `withOffset_usesOffset`, `missingOrZero_returnsNull`)와 `PhotoMatcherTest` 6개를 그대로 쓴다.

**Interfaces:**
- Produces: `ExifTime.parse(dateTime, offset, zone = DEMO_ZONE): Long?`, `distanceMeters(...)`(하버사인, `android.location.Location`은 JVM 테스트 불가), `PhotoMatcher.match(photo, visits): String?`, `RADIUS_M = 300.0`

- [ ] **Step 1: 실패하는 테스트** (v1 Task 3 Step 1 그대로)
- [ ] **Step 2: 실패 확인** → FAIL
- [ ] **Step 3: 구현** — `arrivedAt ≤ takenAt ≤ leftAt`(양끝 포함) + 거리 `RADIUS_M` 이하인 첫 방문. 날짜 파싱 실패·연도 0·NaN 위경도는 null.
- [ ] **Step 4: 통과 확인** → PASS
- [ ] **Step 5: Commit** — `git commit -m "feat: exif time parsing and photo matching"`

### Task 6: 사진 스캔·동기화·확정·해제 (F5, F6 — R08·R09 반영)

**Interfaces:**
- Produces: `MediaStorePhotoSource(context) : PhotoSource`, `MemoryRepository.syncPhotos(date)`, `MemoryRepository.setPhotoState(uri, state)`, `PhotoDao`: `upsert`, `get`, `setState`, `observeAll`

- [ ] **Step 1: 실패하는 테스트** (androidTest, fake PhotoSource)
  - `matchedPhoto_isStoredAsCandidate`: `PhotoMeta("content://p2", t("13:45"), 37.2846, 126.5779)` → `visitId == "v2"`, `CANDIDATE`
  - `unmatchedPhoto_isStoredUnlinked`: `PhotoMeta("content://p7", null, null, null)` → `visitId == null`, `UNLINKED`
  - `rescan_keepsUserDecisionsIntact`: p6 sync → `setPhotoState(REJECTED)` → 재sync → 여전히 `REJECTED`, `visitId`도 그대로
  - `unlinkedPhoto_isReevaluatedOnRescan`: **(R09)** p2를 위치 없이(`lat=null`) sync → `UNLINKED` → 같은 uri를 위치와 함께 재sync → `CANDIDATE`, `visitId == "v2"`
  - `candidatePhoto_isReevaluatedOnRescan`: p2를 `CANDIDATE`로 둔 뒤 좌표가 바뀐 채 재sync → 새 매칭 결과를 따른다
  - `observeDay_putsOnlyCandidateAndConfirmedPhotosOnCards`: p2 CANDIDATE, p6 REJECTED → 해변 카드 photos가 `["content://p2"]`
- [ ] **Step 2: 실패 확인** → FAIL
- [ ] **Step 3: 구현** — `syncPhotos`는 각 사진마다 기존 행을 조회해서, `linkState.isUserDecided`면 그대로 두고, 아니면 매칭을 다시 계산해 `upsert`한다. 이것이 v1의 "insertIfAbsent"가 만들던 고착을 없앤다.
- [ ] **Step 4: 통과 확인** → PASS
- [ ] **Step 5: `MediaStorePhotoSource` 구현** — `MediaStore.Images` 조회 → 각 uri를 `MediaStore.setRequireOriginal(uri)` 스트림으로 열어 `ExifInterface`에서 `TAG_DATETIME_ORIGINAL`·`TAG_OFFSET_TIME_ORIGINAL`·`latLong`을 읽는다. **사진 1장의 예외는 그 사진만 건너뛰고 로그로 남긴다**(R08). 매니페스트에 `READ_MEDIA_IMAGES`, `ACCESS_MEDIA_LOCATION`. 권한 요청은 주차 상태에서만 띄운다.
- [ ] **Step 6: 수동 확인** — Expected: p1→칼국수집, p2·p3·p4·p6→해변, p5→카페에 후보로 붙고 p7은 없다. 위치가 하나도 안 붙으면 `ACCESS_MEDIA_LOCATION`과 `setRequireOriginal`을 다시 본다. 권한을 준 뒤 다시 들어가면 UNLINKED였던 사진이 후보로 올라와야 한다.
- [ ] **Step 7: 카드 UI** — 썸네일 줄 + 길게 눌러 확정/해제. 후보에는 '후보' 표기.
- [ ] **Step 8: Commit** — `git commit -m "feat: scan photos and reevaluate machine-set links"`

### Task 7: 직접 메모 (F7)

v1 Task 5와 동일하다.

- [ ] **Step 1: 실패하는 테스트** — `addNote_keepsTextExactly`(원문 그대로, trim도 하지 않음), `addNote_blankText_savesNothing`
- [ ] **Step 2: 실패 확인** → FAIL
- [ ] **Step 3: 구현** — `addNote(visitId, text, kind, now): Boolean`. `isBlank()`면 false.
- [ ] **Step 4: 통과 확인** → PASS
- [ ] **Step 5: UI** — 카드 아래 메모 목록(VOICE는 마이크 아이콘). TYPED만 수정 가능, 음성 원문은 불변.
  Run: 메모 작성 → 재시작 → Expected: 남아 있다.
- [ ] **Step 6: Commit** — `git commit -m "feat: per-visit notes"`

### Task 8: 공유본 생성 (F8, F9 로직 — R14 반영)

**Interfaces:**
- Produces: `ShareSelection(date, includedVisitIds, selectedPhotoUris, captions)`, `ShareItem(order, placeName, photoUris, caption)`, `ShareDraft(date, items)`, `ShareDraftBuilder.build(selection, day): ShareDraft?`

- [ ] **Step 1: 실패하는 테스트** — 테스트 파일 상단에 `val fixtureDay: DayDraft`를 둔다: 칼국수집(p1 CANDIDATE), 해변(p2 CONFIRMED, p6 REJECTED, note "애들이 바다에서 한참 놀았네"), 카페(p5 CANDIDATE). **`build()`는 nullable이므로 모든 테스트가 먼저 non-null을 검증한다**(R14).

```kotlin
private fun built(include: Set<String> = setOf("v1","v2","v3"),
                  photos: Set<String> = setOf("content://p1","content://p2","content://p5","content://p6"),
                  captions: Map<String,String> = emptyMap()): ShareDraft =
    requireNotNull(ShareDraftBuilder.build(ShareSelection(DEMO_DATE, include, photos, captions), fixtureDay))

@Test fun excludedVisit_isAbsentAndOrderIsRenumbered() {
    val d = built(include = setOf("v1", "v2"))
    assertEquals(listOf("대부도 칼국수집", "방아머리해변"), d.items.map { it.placeName })
    assertEquals(listOf(1, 2), d.items.map { it.order })
}
@Test fun rejectedPhoto_neverIncludedEvenIfSelected() =
    assertFalse(built().items.flatMap { it.photoUris }.contains("content://p6"))
@Test fun photoOfExcludedVisit_notIncluded() =
    assertTrue(built(include = setOf("v2"), photos = setOf("content://p5")).items.all { it.photoUris.isEmpty() })
@Test fun caption_trimmed_blankBecomesNull() {
    val d = built(captions = mapOf("v2" to "  바다에서 한참 놀았어요 ", "v1" to "  "))
    assertEquals("바다에서 한참 놀았어요", d.items.first { it.placeName == "방아머리해변" }.caption)
    assertNull(d.items.first { it.placeName == "대부도 칼국수집" }.caption)
}
@Test fun noteText_neverAppears() = assertFalse(built().toString().contains("애들이"))
@Test fun nothingIncluded_returnsNull() =
    assertNull(ShareDraftBuilder.build(ShareSelection(DEMO_DATE, emptySet(), emptySet(), emptyMap()), fixtureDay))
```

- [ ] **Step 2: 실패 확인** → FAIL
- [ ] **Step 3: 구현** — 카드 순서대로 돌며 포함 방문에만 `order`를 1부터. 사진은 `선택됨 && 그 카드에 실려 있음`만.
- [ ] **Step 4: 통과 확인** → PASS
- [ ] **Step 5: Commit** — `git commit -m "feat: share draft builder"`

### Task 9: 공유 선택·미리보기·내보내기 (F8, F9 UI, F10 — R11·R12 반영)

**Interfaces:**
- Produces: `ShareViewModel`(Activity 범위): `selection`, `draft`, `toggleVisit`, `togglePhoto`, `setCaption`; `ShareImageText.lines(draft): List<String>`, `ShareImageRenderer(context).render(draft, widthPx = 1080): Bitmap`, `ShareExporter(context)`: `saveToGallery(bitmap, displayName): Uri`, `shareIntent(uri): Intent`

- [ ] **Step 1: 실패하는 테스트**

```kotlin
@Test fun lines_haveDateThenNumberedPlacesThenCaptions() {
    val draft = ShareDraft("2026-11-08", listOf(
        ShareItem(1, "대부도 칼국수집", listOf("content://p1"), null),
        ShareItem(2, "방아머리해변", listOf("content://p2"), "바다에서 한참 놀았어요")))
    assertEquals(listOf("2026년 11월 8일", "1. 대부도 칼국수집", "2. 방아머리해변", "바다에서 한참 놀았어요"),
                 ShareImageText.lines(draft))
}
```

- [ ] **Step 2: 실패 확인** → FAIL
- [ ] **Step 3: 구현** — `lines`는 날짜 한 줄 + 방문마다 `"{order}. {placeName}"` + caption 줄. `render`는 세로 캔버스에 그 순서로 글을 쓰고 각 방문 줄 아래에 사진을 폭에 맞춰 다운샘플해 그린다(EXIF 회전 반영). 맨 아래에 "기억 저장소".
- [ ] **Step 4: 통과 확인** → PASS
- [ ] **Step 5: 선택·미리보기 화면** — 선택 화면은 방문 포함 스위치·사진 체크·설명 입력. `draft == null`이면 '미리보기' 비활성. 미리보기는 `ShareDraft`만으로 렌더링한다(원본 엔티티 참조 금지).
- [ ] **Step 6: 내보내기** — `saveToGallery`는 `Pictures/기억저장소/`에 `IS_PENDING = 1`로 행을 만들고, 스트림에 PNG를 다 쓴 뒤 `IS_PENDING = 0`으로 공개한다. 중간에 실패하면 **이번에 만든 행만** 삭제한다(R12). 성공하면 `Intent.createChooser(shareIntent(uri), null)`. 새 PNG로 인코딩하므로 원본 EXIF·GPS는 따라오지 않는다.
  **내보내기 버튼을 누른 시점에 주차 상태를 다시 확인하고, 주차가 아니면 중단한다**(R11).
- [ ] **Step 7: 수동 확인** — 카페 제외, p3 해제, 해변 설명 입력 → 미리보기에 칼국수집·해변만, p3 없음, 음성 메모 문구 없음 → 내보내기 → 갤러리 이미지에도 카페·메모·좌표 없음.
- [ ] **Step 8: Commit** — `git commit -m "feat: share selection, preview and image export"`

### Task 10: 주행 상태 게이트 (F1 — R06·R07 반영)

**Interfaces:**
- Produces: `enum class DriveState { DRIVING, PARKED, UNKNOWN }`, `enum class Route { Driving, DayDraft, ShareSelect, SharePreview }`, `RouteGate.routeFor(state, requested): Route`, `interface DriveStateSource { val state: StateFlow<DriveState> }`, `UxRestrictionsDriveStateSource(context)`

- [ ] **Step 1: 실패하는 테스트**

```kotlin
@Test fun drivingOrUnknown_overridesEveryRoute() {
    listOf(DriveState.DRIVING, DriveState.UNKNOWN).forEach { s ->
        Route.values().forEach { r -> assertEquals(Route.Driving, RouteGate.routeFor(s, r)) }
    }
}
@Test fun parkedOnDrivingRoute_goesToDayDraft() = assertEquals(Route.DayDraft, RouteGate.routeFor(DriveState.PARKED, Route.Driving))
@Test fun parked_keepsRequestedRoute() = assertEquals(Route.ShareSelect, RouteGate.routeFor(DriveState.PARKED, Route.ShareSelect))
```

- [ ] **Step 2: 실패 확인** → FAIL
- [ ] **Step 3: 구현** — 위 세 규칙 그대로. `UNKNOWN`은 Task 2 Step 4에서 본 대로 **초기값**이며, 첫 UX 제한 값이 오기 전까지 유지한다.
- [ ] **Step 4: 통과 확인** → PASS
- [ ] **Step 5: 플랫폼 연결** — `UxRestrictionsDriveStateSource`는 `Car.createCar` → `CarUxRestrictionsManager`에서 `isRequiresDistractionOptimization`으로 DRIVING/PARKED를 내고, 연결 전·끊김은 UNKNOWN을 낸다. 화면 구조는 **Task 2 Step 5의 판정을 따른다**: 분리 가능하면 `DrivingActivity`(distractionOptimized) + `MainActivity`(메타데이터 없음), 아니면 단일 Activity + RouteGate.
  `DrivingScreen`은 화면 중앙의 큰 '한마디 남기기' 버튼 하나만 둔다. UNKNOWN일 때는 "차량 상태 확인 중"만 보여주고 버튼도 비활성.
- [ ] **Step 6: 수동 확인** — 공유 선택 화면에서 기어를 D로 → 주행 화면으로 전환. 다시 P로 → 하루 초안, 공유 선택을 다시 열면 이전 선택이 남아 있다. 앱을 새로 띄운 직후 잠깐 "차량 상태 확인 중"이 보일 수 있고, 그동안 편집이 열리지 않는다.
- [ ] **Step 7: Commit** — `git commit -m "feat: lock editing while driving or state unknown"`

**Day 2 관문:** P0 공유 흐름 완성. 못 넘으면 Task 11은 Task 2에서 정한 대체 경로(텍스트 입력)로 줄이고 Day 3를 Task 12에 쓴다.

---

## Day 3 · 11/15(일) — 관문: 21시 제출

### Task 11: 음성 메모 (F2 — R10 반영)

**Interfaces:**
- Produces: `sealed interface SttResult { Text(value, completed: Boolean); Error(reason) }`, `fun interface SpeechInput { fun listen(): Flow<SttResult> }`, `PleosSpeechInput(context)`(Mode.ON_DEVICE 고정), `AndroidSpeechInput(context)`(ko-KR, 온디바이스), `targetVisit(visits, now): Visit?`, `VoiceMemoOutcome { Saved(visitId); NothingHeard; NoVisit; Failed; Busy }`, `VoiceMemoUseCase(visits, addNote, now)`: `suspend fun record(input: SpeechInput): VoiceMemoOutcome`

- [ ] **Step 1: 실패하는 테스트**

```kotlin
@Test fun afterLeavingBeach_targetsBeach() = assertEquals("v2", targetVisit(day, t("14:58"))?.id)
@Test fun duringCafe_targetsCafe() = assertEquals("v3", targetVisit(day, t("15:30"))?.id)
@Test fun beforeFirstVisit_targetsNothing() = assertNull(targetVisit(day, t("11:00")))

@Test fun finalResult_isSavedVerbatimOnce() = runTest {
    val saved = mutableListOf<Pair<String, String>>()
    val uc = VoiceMemoUseCase({ day }, { v, s -> saved += v to s; true }, { t("14:58") })
    val out = uc.record { flowOf(SttResult.Text("애들이", false), SttResult.Text("애들이 바다에서 한참 놀았네", true)) }
    assertEquals(VoiceMemoOutcome.Saved("v2"), out)
    assertEquals(listOf("v2" to "애들이 바다에서 한참 놀았네"), saved)   // 중간 결과는 저장되지 않음
}
@Test fun partialResult_isNotSaved() = runTest { /* Text("애들이", false)만 흘리고 끝 → NothingHeard, saved 비어 있음 */ }
@Test fun blankText_savesNothing() = runTest { /* Text("  ", true) → NothingHeard */ }
@Test fun sttError_savesNothing() = runTest { /* Error("engine") → Failed */ }
@Test fun noVisitYet_savesNothing() = runTest { /* now = t("11:00") → NoVisit */ }
@Test fun secondTapWhileListening_isRejected() = runTest { /* 녹음 중 record() 재호출 → Busy, saved 그대로 */ }
@Test fun timeout_savesNothing() = runTest { /* 15초간 completed가 오지 않음 → NothingHeard */ }
```

(주석 처리한 테스트는 첫 테스트와 같은 구조로, 표시한 입력·결과만 바꾼다.)

- [ ] **Step 2: 실패 확인** → FAIL
- [ ] **Step 3: 구현** — `record`는 진입 시 녹음 중이면 `Busy`를 돌려준다. **시작 시점에 `targetVisit`을 한 번 고정**하고(녹음 중 시각이 흘러도 대상이 바뀌지 않는다), `completed = true`인 결과 하나만 저장한다. 15초 안에 완료가 없으면 `NothingHeard`. 공백은 저장하지 않고, 유효한 입력은 원문 그대로 저장한다.
- [ ] **Step 4: 통과 확인** → PASS
- [ ] **Step 5: 플랫폼 연결** — Task 2 판정대로 `PleosSpeechInput`(`SpeechToText(context, Mode.ON_DEVICE)`, `registerApp` 호출 없음) 또는 `AndroidSpeechInput`을 붙인다. 두 경로가 다 막혔으면 주차 중 텍스트 입력으로 대체하고 PRD를 고친다. `DrivingScreen` 버튼은 결과별로 "해변에 남겼어요" / "다시 말씀해 주세요" / "아직 방문 기록이 없어요" / "녹음 중이에요"를 2초간 보여준다.
- [ ] **Step 6: 수동 확인** — 기어 D → 버튼 → "애들이 바다에서 한참 놀았네" → 기어 P → 해변 카드에 마이크 아이콘 메모가 원문 그대로 하나만 있다.
- [ ] **Step 7: Commit** — `git commit -m "feat: voice memo while driving"`

### Task 12: 검증·제출물 (R17 반영)

- [ ] **Step 1: PRD 검증 체크리스트 8개 실행** — PRD '검증과 시연 영상'의 항목을 하나씩 확인하고 PRD에서 체크한다. 여기에 두 가지를 더한다: 앱 시작 직후 UNKNOWN 구간에서 편집이 열리지 않는지, 위치 권한을 나중에 준 뒤 UNLINKED 사진이 후보로 올라오는지.
- [ ] **Step 2: 비밀정보 점검** — ON_DEVICE 전용이라 Pleos 자격증명 자체가 없다. 그래도 제출 전에 확인한다.
  ```bash
  grep -rniE "client_?(id|secret)|api[_-]?key|nexus-playground" app/src --include=* ; echo "---"
  unzip -p app/build/outputs/apk/debug/app-debug.apk AndroidManifest.xml | strings | grep -i internet
  ```
  Expected: 첫 명령은 `build.gradle.kts`의 저장소 URL 외에 걸리는 것이 없고, 두 번째는 아무것도 나오지 않는다(INTERNET 권한 없음).
- [ ] **Step 3: README** — 실행 환경(Pleos Connect Emulator 2.1.0, API 34, cold boot, 마이크 ON), minSdk 29, 사진 주입 명령, 데모 흐름 7단계, "방문은 재생용 시드 데이터이며 실제 감지는 범위 밖", "STT는 온디바이스 전용, 서버 전송 없음"을 적는다. 실명·연락처·계정 정보는 넣지 않는다.
- [ ] **Step 4: APK·영상** — `./gradlew assembleDebug`. PRD 영상 구성대로 촬영하고 3단계에 "휴대폰에서 가져온 사진" 자막을 넣는다.
- [ ] **Step 5: Commit & 제출** — `git commit -m "docs: demo readme and samples"` → 21시 제출

---

## 시간이 남을 때 (P1)

P0가 모두 끝나고 검증까지 통과한 뒤에만 손댄다. F13(음성 메모 다듬기)과 3D 기억 맵은 이번 범위에서 보류한다.

### Task 13: 출처 배지 확장(F11) + 사진 옮기기(F12)

- Produces: `MemoryRepository.movePhoto(uri, toVisitId)` (상태는 CONFIRMED로)
- [ ] 실패하는 테스트 `movePhoto_reassignsAndConfirms`(androidTest) → 실패 확인 → 구현 → 통과 → Commit
- [ ] UI: 방문 '시연 데이터', 사진 '후보/확정', 메모 '내가 남긴 말/음성' 배지. 길게 누르기 메뉴에 '다른 방문으로' 추가. 수동 확인 → Commit
