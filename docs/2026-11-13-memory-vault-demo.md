# 기억 저장소 데모 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pleos Connect 에뮬레이터에서 '방문 → 하루 초안 → 사진·메모 수정 → 공유 선택 → 이미지 내보내기'와 주행 중 음성 메모 1경로가 동작하는 데모 APK를 11/15 21시까지 만든다.

**Architecture:** 단일 AAOS 앱(Kotlin + Compose). 모든 기록은 Room에 두고, 판단 로직(사진 매칭, 공유본 생성, 화면 게이트, 음성 메모 대상 방문)은 Android 의존성이 없는 순수 Kotlin 객체로 분리해 JVM 단위 테스트로 검증한다. Android/Pleos에 닿는 부분(MediaStore, UX 제한, STT)은 인터페이스 뒤에 두어 교체 가능하게 한다.

**Tech Stack:** Kotlin, Jetpack Compose, Navigation Compose, Room(KSP), kotlinx.serialization, androidx.exifinterface, AAOS `android.car`(CarUxRestrictionsManager), Pleos SpeechToText SDK, JUnit4, kotlinx-coroutines-test, Room in-memory(androidTest)

**Spec:** 기억 저장소 데모 PRD.PDF 참고. `docs/superpowers/specs/2026-11-13-memory-vault-demo-design.md`에 함께 커밋)

**개발자:** 1인. 태스크는 PRD 일정의 하루 단위로 묶었다. 각 Day 끝의 관문을 못 넘으면 PRD '개발 일정'의 컷 규칙을 따른다.

## Global Constraints

- minSdk 26, Gradle 8.0 이상, Kotlin. 테스트 기기는 Pleos Connect Emulator AVD, Boot option = **cold boot**(quick boot면 VHAL 사용 불가), 에뮬레이터 마이크 입력 ON.
- 패키지명 `app.memoryvault`. Pleos SDK 의존성 블록은 Pleos 개발 환경 가이드의 것을 그대로 쓴다.
- 시간대는 항상 `Asia/Seoul` 고정(`DEMO_ZONE`). 에뮬레이터 기본 시간대(GMT일 수 있음)를 쓰지 않는다.
- 데이터는 기기 로컬(Room)에만 둔다. 네트워크 호출 없음(P1의 LLM 제외).
- 공유 이미지에는 **포함한 방문의 이름·순서·선택한 사진·공유용 설명만** 들어간다. Note, 좌표, 주소, 제외한 방문은 공유본 타입에 필드조차 없다.
- 주행 중(DRIVING)에는 `Route.Driving` 외의 화면을 띄우지 않는다.
    - 해당 화면 위에 녹음 중이라면 녹음 중인 것을 확인 가능하게 표기한다.(Route.Driving의 기능에 크게 관여하지 않도록 해야한다)
- AI(LLM)는 사용자가 말하지 않은 감정·사실·동행자를 추가하지 않는다. 원문은 항상 보존한다.
- 매니페스트에는 실제로 쓰는 권한만 선언한다.

## Review Focus

1. **EXIF 시각에 시간대가 없음**: `DateTimeOriginal`은 오프셋 없는 현지 시각이다. 기기 시간대로 읽으면 9시간 밀려 어떤 사진도 매칭되지 않는다 → `OffsetTimeOriginal`이 있으면 그것, 없으면 Asia/Seoul로 해석(Task 3 `noOffset_readsAsSeoulTime`).
2. **앱 재시작**: 시드가 두 번 들어가 방문이 중복되거나, 다시 스캔할 때 해제한 사진이 후보로 되살아나면 시연이 깨진다 → 중복 없음·상태 유지(Task 1 `seedTwice_keepsThreeVisits`, Task 4 `rescan_keepsRejectedPhotoRejected`).
3. **사진 GPS가 지워져서 읽힘**: API 29+에서 `ACCESS_MEDIA_LOCATION` 권한과 `MediaStore.setRequireOriginal()` 없이 열면 위치가 제거된 사본이 온다 → 모든 사진이 '위치 없음'으로 빠진다(Task 4 수동 확인 단계).
4. **편집 중 차가 출발함**: 공유 선택 화면에 있다가 주행 상태가 되면 즉시 주행 화면으로 가야 한다. 다시 주차하면 하루 초안으로 돌아온다(Task 10 `driving_overridesEveryRoute`, `parkedOnDrivingRoute_goesToDayDraft`).
5. **STT가 빈 문자열·오류를 돌려줌**: 빈 메모가 저장되거나 앱이 멈추면 안 된다(Task 11 `blankText_savesNothing`, `sttError_savesNothing`).

---

## File Structure

```
app/src/main/
  AndroidManifest.xml
  assets/seed_day.json                       # 시연용 하루(방문 3곳 + demoNow)
  java/app/memoryvault/
    MainActivity.kt                          # NavHost + 주행 게이트 관찰
    DemoConfig.kt                            # DEMO_ZONE, 시드 날짜
    data/
      Entities.kt                            # Visit, Photo, Note, LinkState, NoteKind
      AppDatabase.kt  Daos.kt                # Room
      SeedLoader.kt                          # JSON → SeedDay
      PhotoSource.kt                         # interface + MediaStorePhotoSource
      ExifTime.kt                            # EXIF 시각 파싱(순수)
      MemoryRepository.kt                    # 시드·사진 동기화·상태·메모, DayDraft 조립
    domain/
      Geo.kt  PhotoMatcher.kt                # 거리 + 후보 매칭(순수)
      ShareDraft.kt                          # ShareSelection, ShareDraft, ShareDraftBuilder(순수)
      RouteGate.kt                           # DriveState, Route, RouteGate(순수)
      VoiceMemo.kt                           # targetVisit, VoiceMemoUseCase
    platform/
      DriveStateSource.kt                    # interface + UxRestrictionsDriveStateSource
      SpeechInput.kt                         # interface + PleosSpeechInput + AndroidSpeechInput
      ShareImage.kt                          # ShareImageText(순수) + ShareImageRenderer + ShareExporter
    ui/
      DrivingScreen.kt
      DayDraftScreen.kt  DayDraftViewModel.kt
      ShareSelectScreen.kt  SharePreviewScreen.kt  ShareViewModel.kt
app/src/test/java/app/memoryvault/...        # JVM 단위 테스트
app/src/androidTest/java/app/memoryvault/... # Room·Repository 테스트(에뮬레이터)
samples/                                     # 시연용 사진 7장(EXIF 설정 후 adb push)
```

공용 테스트 헬퍼 `app/src/test/java/app/memoryvault/Fixtures.kt`(androidTest에도 같은 파일 복사):

```kotlin
fun t(hm: String): Long = OffsetDateTime.parse("2026-11-08T$hm:00+09:00").toInstant().toEpochMilli()
val restaurant = Visit("v1", "2026-11-08", "대부도 칼국수집", 37.2830, 126.5810, t("12:00"), t("13:10"))
val beach      = Visit("v2", "2026-11-08", "방아머리해변",   37.2846, 126.5779, t("13:30"), t("14:50"))
val cafe       = Visit("v3", "2026-11-08", "바다 앞 카페",   37.2790, 126.5850, t("15:10"), t("16:00"))
val day = listOf(restaurant, beach, cafe)
```

좌표는 시연용 근사값이다. 이 값들이 `seed_day.json`과 일치해야 한다.

---

## Day 1 · 11/13(금) 저녁 — 관문: 초안 화면에 시드 방문 3곳이 보임

### Task 1: 프로젝트 골격 + 시드 방문 → Room (F3)

**Files:**
- Create: Android Studio 'Empty Activity(Compose)' 프로젝트, `DemoConfig.kt`, `data/Entities.kt`, `data/AppDatabase.kt`, `data/Daos.kt`, `data/SeedLoader.kt`, `assets/seed_day.json`
- Test: `test/.../data/SeedLoaderTest.kt`, `androidTest/.../data/VisitDaoTest.kt`

**Interfaces:**
- Produces:
  - `val DEMO_ZONE: ZoneId = ZoneId.of("Asia/Seoul")`, `const val DEMO_DATE = "2026-11-08"`
  - `@Entity("visits") data class Visit(@PrimaryKey val id: String, val date: String, val placeName: String, val lat: Double, val lng: Double, val arrivedAt: Long, val leftAt: Long)`
  - `@Entity("photos") data class Photo(@PrimaryKey val uri: String, val takenAt: Long?, val lat: Double?, val lng: Double?, val visitId: String?, val linkState: LinkState)`
  - `enum class LinkState { CANDIDATE, CONFIRMED, REJECTED, UNLINKED }`
  - `@Entity("notes") data class Note(@PrimaryKey(autoGenerate = true) val id: Long = 0, val visitId: String, val text: String, val kind: NoteKind, val createdAt: Long, val refinedText: String? = null)`
  - `enum class NoteKind { TYPED, VOICE }`
  - `data class SeedDay(val date: String, val demoNow: Long, val visits: List<Visit>)`, `object SeedLoader { fun parse(json: String): SeedDay }`
  - `VisitDao`: `insertAll(visits: List<Visit>)`(OnConflict IGNORE), `observeDay(date: String): Flow<List<Visit>>`, `getDay(date: String): List<Visit>` (둘 다 `ORDER BY arrivedAt`)

- [ ] **Step 1: 프로젝트 생성·에뮬레이터 확인**

새 프로젝트를 만들고, `build.gradle.kts`에 Room(KSP)·kotlinx.serialization·exifinterface·navigation-compose·Pleos SDK 의존성과 `android { useLibrary("android.car") }`를 추가한다. 매니페스트에 `<uses-feature android:name="android.hardware.type.automotive" android:required="true"/>`를 넣는다.
Run: Pleos Connect Emulator(cold boot)에서 실행
Expected: 빈 Compose 화면이 뜬다. 에뮬레이터의 차량 데이터 패널에서 기어·속도를 바꿀 수 있다.

- [ ] **Step 2: `seed_day.json` 작성**

```json
{ "date": "2026-11-08", "demoNow": "2026-11-08T14:58:00+09:00",
  "visits": [
    {"id":"v1","placeName":"대부도 칼국수집","lat":37.2830,"lng":126.5810,"arrivedAt":"2026-11-08T12:00:00+09:00","leftAt":"2026-11-08T13:10:00+09:00"},
    {"id":"v2","placeName":"방아머리해변","lat":37.2846,"lng":126.5779,"arrivedAt":"2026-11-08T13:30:00+09:00","leftAt":"2026-11-08T14:50:00+09:00"},
    {"id":"v3","placeName":"바다 앞 카페","lat":37.2790,"lng":126.5850,"arrivedAt":"2026-11-08T15:10:00+09:00","leftAt":"2026-11-08T16:00:00+09:00"} ] }
```

`demoNow`(14:58)는 해변을 떠나 카페로 가는 중이다. 음성 메모가 해변에 붙는 근거가 된다(Task 11).

- [ ] **Step 3: 실패하는 테스트 작성**

```kotlin
class SeedLoaderTest {
    @Test fun parsesVisitsInTimeOrder() {
        val shuffled = """{"date":"2026-11-08","demoNow":"2026-11-08T14:58:00+09:00","visits":[
          {"id":"v2","placeName":"방아머리해변","lat":37.2846,"lng":126.5779,"arrivedAt":"2026-11-08T13:30:00+09:00","leftAt":"2026-11-08T14:50:00+09:00"},
          {"id":"v1","placeName":"대부도 칼국수집","lat":37.2830,"lng":126.5810,"arrivedAt":"2026-11-08T12:00:00+09:00","leftAt":"2026-11-08T13:10:00+09:00"}]}"""
        val seed = SeedLoader.parse(shuffled)
        assertEquals(listOf("v1", "v2"), seed.visits.map { it.id })
        assertEquals(t("12:00"), seed.visits[0].arrivedAt)
        assertEquals("2026-11-08", seed.visits[0].date)
        assertEquals(t("14:58"), seed.demoNow)
    }
    @Test(expected = IllegalArgumentException::class) fun leftBeforeArrived_isRejected() {
        SeedLoader.parse("""{"date":"2026-11-08","demoNow":"2026-11-08T14:58:00+09:00","visits":[
          {"id":"v1","placeName":"x","lat":0.0,"lng":0.0,"arrivedAt":"2026-11-08T13:00:00+09:00","leftAt":"2026-11-08T12:00:00+09:00"}]}""")
    }
    @Test fun realSeedFile_matchesFixtures() {
        val seed = SeedLoader.parse(File("src/main/assets/seed_day.json").readText())
        assertEquals(day, seed.visits)
    }
}
```

`VisitDaoTest`(in-memory Room): `seedTwice_keepsThreeVisits`. `insertAll(day)`를 두 번 호출한 뒤 `getDay("2026-11-08").size == 3`이고 id 순서가 `v1, v2, v3`인지 확인한다.

- [ ] **Step 4: 실패 확인**

Run: `./gradlew testDebugUnitTest --tests "app.memoryvault.data.SeedLoaderTest"`
Expected: FAIL (SeedLoader 미정의)

- [ ] **Step 5: 구현**

`SeedLoader.parse`는 kotlinx.serialization으로 JSON DTO를 읽는다. org.json은 JVM 단위 테스트에서 "not mocked" 예외를 내므로 쓰지 않는다. 시각은 `OffsetDateTime.parse`로 읽어 epoch ms로 바꾸고, `arrivedAt` 순으로 정렬한다. `leftAt < arrivedAt`이면 `IllegalArgumentException`을 던진다. 이어서 Room 엔티티·DAO·`AppDatabase`(TypeConverter 없이 enum은 Room 기본 처리)를 만든다.

- [ ] **Step 6: 통과 확인**

Run: `./gradlew testDebugUnitTest --tests "app.memoryvault.data.SeedLoaderTest"` → PASS
Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.memoryvault.data.VisitDaoTest` → PASS

- [ ] **Step 7: Commit** — `git commit -m "feat: seed visits into Room"`

### Task 2: MemoryRepository 기본 + 하루 초안 화면 (F4)

**Files:**
- Create: `data/MemoryRepository.kt`, `ui/DayDraftScreen.kt`, `ui/DayDraftViewModel.kt`, `MainActivity.kt`
- Test: `androidTest/.../data/MemoryRepositoryTest.kt`

**Interfaces:**
- Consumes: Task 1의 엔티티·DAO·`SeedLoader`
- Produces:
  - `data class VisitCard(val visit: Visit, val photos: List<Photo>, val notes: List<Note>)`, `data class DayDraft(val date: String, val cards: List<VisitCard>)`
  - `class MemoryRepository(db: AppDatabase, photoSource: PhotoSource)`: `suspend fun seedIfEmpty(seed: SeedDay)`, `fun observeDay(date: String): Flow<DayDraft>` (Task 4·5에서 메서드 추가)

- [ ] **Step 1: 실패하는 테스트** — `observeDay_returnsCardsInTimeOrder`: `seedIfEmpty(SeedDay(DEMO_DATE, t("14:58"), day))` 후 `observeDay(DEMO_DATE).first().cards.map { it.visit.id } == listOf("v1","v2","v3")`이고, 각 카드의 photos·notes가 비어 있음을 확인한다. `photoSource`에는 빈 목록을 돌려주는 fake를 넣는다(인터페이스는 Task 4에서 정의하므로, 이 태스크에서 `fun interface PhotoSource { suspend fun scan(): List<PhotoMeta> }`와 `data class PhotoMeta(val uri: String, val takenAt: Long?, val lat: Double?, val lng: Double?)`를 `data/PhotoSource.kt`에 먼저 선언한다).
- [ ] **Step 2: 실패 확인** — `connectedDebugAndroidTest ... MemoryRepositoryTest` → FAIL
- [ ] **Step 3: 구현** — `observeDay`는 visit·photo·note 세 Flow를 `combine`해 visitId로 묶는다.
- [ ] **Step 4: 통과 확인** → PASS
- [ ] **Step 5: 화면** — `MainActivity`는 앱 시작 시 `assets/seed_day.json`으로 `seedIfEmpty`를 호출한다. `DayDraftScreen`은 카드마다 장소명과 `HH:mm–HH:mm`(DEMO_ZONE 기준)을 보여준다. 이 시점에는 사진·메모 영역을 빈칸으로 둔다.
  Run: 에뮬레이터 실행 → Expected: "대부도 칼국수집 12:00–13:10", "방아머리해변 13:30–14:50", "바다 앞 카페 15:10–16:00" 순서로 카드가 보인다. 앱을 재시작해도 카드는 3장이다.
- [ ] **Step 6: Commit** — `git commit -m "feat: day draft screen with seeded visits"`

**Day 1 관문:** 초안 화면에 시드 방문 3곳이 보임.

---

## Day 2 · 11/14(토) — 관문: P0 공유 흐름 완성(F1, F3~F10)

### Task 3: EXIF 시각 파싱 + 사진 후보 매칭 (F5 로직)

**Files:**
- Create: `data/ExifTime.kt`, `domain/Geo.kt`, `domain/PhotoMatcher.kt`
- Test: `test/.../data/ExifTimeTest.kt`, `test/.../domain/PhotoMatcherTest.kt`

**Interfaces:**
- Consumes: `Visit`, `PhotoMeta`, `DEMO_ZONE`
- Produces:
  - `object ExifTime { fun parse(dateTime: String?, offset: String?, zone: ZoneId = DEMO_ZONE): Long? }`
  - `fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double` (하버사인. `android.location.Location`은 JVM 테스트에서 못 씀)
  - `object PhotoMatcher { const val RADIUS_M = 300.0; fun match(photo: PhotoMeta, visits: List<Visit>): String? }`

- [ ] **Step 1: 실패하는 테스트**

```kotlin
class ExifTimeTest {
    @Test fun noOffset_readsAsSeoulTime() = assertEquals(t("14:05"), ExifTime.parse("2026:11:08 14:05:00", null))
    @Test fun withOffset_usesOffset() = assertEquals(t("14:05"), ExifTime.parse("2026:11:08 05:05:00", "+00:00"))
    @Test fun missingOrZero_returnsNull() {
        assertNull(ExifTime.parse(null, null)); assertNull(ExifTime.parse("0000:00:00 00:00:00", null))
    }
}
class PhotoMatcherTest {
    @Test fun insideWindowAndNear_matchesThatVisit() = assertEquals("v2", PhotoMatcher.match(PhotoMeta("p", t("14:05"), 37.2847, 126.5780), day))
    @Test fun exactlyAtLeftAt_matches() = assertEquals("v2", PhotoMatcher.match(PhotoMeta("p", t("14:50"), 37.2846, 126.5779), day))
    @Test fun noTime_isNotPlaced() = assertNull(PhotoMatcher.match(PhotoMeta("p", null, 37.2846, 126.5779), day))
    @Test fun noLocation_isNotPlaced() = assertNull(PhotoMatcher.match(PhotoMeta("p", t("14:05"), null, null), day))
    @Test fun inWindowButFar_isNotPlaced() = assertNull(PhotoMatcher.match(PhotoMeta("p", t("14:05"), 37.5665, 126.9780), day))
    @Test fun betweenVisits_isNotPlaced() = assertNull(PhotoMatcher.match(PhotoMeta("p", t("13:20"), 37.2830, 126.5810), day))
}
```

- [ ] **Step 2: 실패 확인** — `./gradlew testDebugUnitTest --tests "app.memoryvault.*Exif*" --tests "app.memoryvault.domain.PhotoMatcherTest"` → FAIL
- [ ] **Step 3: 구현** — `ExifTime`은 `yyyy:MM:dd HH:mm:ss`를 `LocalDateTime`으로 읽고, offset이 있으면 `ZoneOffset.of(offset)`, 없으면 `zone`을 붙인다. 파싱 실패와 연도 0은 null로 처리한다. `match`는 `arrivedAt ≤ takenAt ≤ leftAt`(양끝 포함)이면서 거리가 `RADIUS_M` 이하인 첫 방문의 id를 돌려준다.
- [ ] **Step 4: 통과 확인** → PASS
- [ ] **Step 5: Commit** — `git commit -m "feat: exif time parsing and photo matching"`

### Task 4: MediaStore 사진 스캔 + 동기화·확정·해제 (F5, F6)

**Files:**
- Modify: `data/PhotoSource.kt`(구현체 추가), `data/MemoryRepository.kt`, `data/Daos.kt`, `AndroidManifest.xml`
- Test: `androidTest/.../data/MemoryRepositoryTest.kt`

**Interfaces:**
- Consumes: `PhotoMatcher.match`, `ExifTime.parse`
- Produces:
  - `class MediaStorePhotoSource(context: Context) : PhotoSource`
  - `MemoryRepository.syncPhotos(date: String)`, `MemoryRepository.setPhotoState(uri: String, state: LinkState)`
  - `PhotoDao`: `insertIfAbsent(photos: List<Photo>)`(IGNORE), `get(uri: String): Photo?`, `setState(uri: String, state: LinkState)`, `observeAll(): Flow<List<Photo>>`

- [ ] **Step 1: 실패하는 테스트** (fake `PhotoSource`, in-memory DB, 시드 후)
  - `matchedPhoto_isStoredAsCandidate`: `PhotoMeta("content://p2", t("13:45"), 37.2846, 126.5779)` → `syncPhotos` 후 `get(...)`의 `visitId == "v2"`, `linkState == CANDIDATE`
  - `unmatchedPhoto_isStoredUnlinked`: `PhotoMeta("content://p7", null, null, null)` → `visitId == null`, `linkState == UNLINKED`
  - `rescan_keepsRejectedPhotoRejected`: p6(`t("14:12")`, 해변 좌표) sync → `setPhotoState(REJECTED)` → 다시 sync → 여전히 `REJECTED`
  - `observeDay_putsOnlyCandidateAndConfirmedPhotosOnCards`: p2 CANDIDATE, p6 REJECTED일 때 해변 카드의 photos uri 목록이 `["content://p2"]`
- [ ] **Step 2: 실패 확인** → FAIL
- [ ] **Step 3: 구현** — `syncPhotos`는 스캔 결과를 매칭해 `insertIfAbsent`로 넣는다. 이미 있는 행은 건드리지 않으므로 사용자가 정한 상태가 유지된다. `observeDay`는 CANDIDATE·CONFIRMED만 카드에 싣는다.
- [ ] **Step 4: 통과 확인** → PASS
- [ ] **Step 5: `MediaStorePhotoSource` 구현** — `MediaStore.Images` 전체를 조회한다. 각 uri를 `MediaStore.setRequireOriginal(uri)`로 연 스트림으로 `ExifInterface`를 만들고, `TAG_DATETIME_ORIGINAL`·`TAG_OFFSET_TIME_ORIGINAL`·`latLong`을 읽는다. 매니페스트에 `READ_MEDIA_IMAGES`, `READ_EXTERNAL_STORAGE`(maxSdkVersion 32), `ACCESS_MEDIA_LOCATION`을 선언하고 런타임에 요청한다.
- [ ] **Step 6: 수동 확인** — 시연 사진(Task 12 Step 1)을 넣고 실행한다.
  Expected: p1이 칼국수집, p2·p3·p4·p6이 해변, p5가 카페 카드에 후보로 붙고, p7은 어디에도 없다. 위치가 하나도 안 붙으면 `ACCESS_MEDIA_LOCATION`과 `setRequireOriginal`을 다시 확인한다.
- [ ] **Step 7: 카드 UI** — 카드에 사진 썸네일 줄을 추가하고, 썸네일을 길게 누르면 확정/해제 메뉴가 뜨게 한다. 해제하면 썸네일이 사라진다.
- [ ] **Step 8: Commit** — `git commit -m "feat: scan photos and confirm or reject candidates"`

### Task 5: 직접 메모 (F7, F2 저장소)

**Files:**
- Modify: `data/MemoryRepository.kt`, `data/Daos.kt`, `ui/DayDraftScreen.kt`
- Test: `androidTest/.../data/MemoryRepositoryTest.kt`

**Interfaces:**
- Produces: `suspend fun MemoryRepository.addNote(visitId: String, text: String, kind: NoteKind, now: Long): Boolean`, `NoteDao.insert(note: Note)`, `NoteDao.observeAll(): Flow<List<Note>>`, `NoteDao.update(note: Note)`

- [ ] **Step 1: 실패하는 테스트**
  - `addNote_keepsTextExactly`: `addNote("v2", "애들이 바다에서 한참 놀았네", VOICE, t("14:58"))` → true, 해변 카드 notes[0].text가 입력과 정확히 같다.
  - `addNote_blankText_savesNothing`: `addNote("v2", "  \n", TYPED, ...)` → false, notes 비어 있음
- [ ] **Step 2: 실패 확인** → FAIL
- [ ] **Step 3: 구현** — `text.isBlank()`면 false를 돌려준다. 저장할 때는 trim도 하지 않는다(원문 보존).
- [ ] **Step 4: 통과 확인** → PASS
- [ ] **Step 5: UI** — 카드 아래에 메모 목록을 보여준다. VOICE는 마이크 아이콘으로 구분한다. '메모 추가' 버튼으로 텍스트 필드를 열고, 기존 TYPED 메모를 눌러 수정할 수 있게 한다.
  Run: 해변 카드에 메모 작성 → 앱 재시작 → Expected: 메모가 남아 있다.
- [ ] **Step 6: Commit** — `git commit -m "feat: per-visit notes"`

### Task 6: 공유본 생성 (F8, F9 로직)

**Files:**
- Create: `domain/ShareDraft.kt`
- Test: `test/.../domain/ShareDraftBuilderTest.kt`

**Interfaces:**
- Consumes: `DayDraft`, `VisitCard`, `Photo`, `LinkState`
- Produces:
  - `data class ShareSelection(val date: String, val includedVisitIds: Set<String>, val selectedPhotoUris: Set<String>, val captions: Map<String, String>)`
  - `data class ShareItem(val order: Int, val placeName: String, val photoUris: List<String>, val caption: String?)`
  - `data class ShareDraft(val date: String, val items: List<ShareItem>)`
  - `object ShareDraftBuilder { fun build(selection: ShareSelection, day: DayDraft): ShareDraft? }` (포함 방문이 0개면 null)

- [ ] **Step 1: 실패하는 테스트** — 테스트 파일 상단의 `val fixtureDay: DayDraft`: 칼국수집(p1 CANDIDATE), 해변(p2 CONFIRMED, p6 REJECTED, note "애들이 바다에서 한참 놀았네"), 카페(p5 CANDIDATE).

```kotlin
@Test fun excludedVisit_isAbsentAndOrderIsRenumbered() {
    val d = build(include = setOf("v1", "v2"))!!
    assertEquals(listOf("대부도 칼국수집", "방아머리해변"), d.items.map { it.placeName })
    assertEquals(listOf(1, 2), d.items.map { it.order })
}
@Test fun rejectedPhoto_neverIncludedEvenIfSelected() = assertFalse(build(photos = setOf("content://p6")).items.flatMap { it.photoUris }.contains("content://p6"))
@Test fun photoOfExcludedVisit_notIncluded() = assertTrue(build(include = setOf("v2"), photos = setOf("content://p5")).items.all { it.photoUris.isEmpty() })
@Test fun caption_trimmed_blankBecomesNull() {
    val d = build(captions = mapOf("v2" to "  바다에서 한참 놀았어요 ", "v1" to "  "))!!
    assertEquals("바다에서 한참 놀았어요", d.items.first { it.placeName == "방아머리해변" }.caption)
    assertNull(d.items.first { it.placeName == "대부도 칼국수집" }.caption)
}
@Test fun noteText_neverAppears() = assertFalse(build().toString().contains("애들이"))
@Test fun nothingIncluded_returnsNull() = assertNull(ShareDraftBuilder.build(ShareSelection(DEMO_DATE, emptySet(), emptySet(), emptyMap()), fixtureDay))
```

(`build(...)`는 기본값 include = v1·v2·v3, photos = 전체, captions = 빈 맵인 테스트 내 헬퍼)

- [ ] **Step 2: 실패 확인** → FAIL
- [ ] **Step 3: 구현** — 카드 순서대로 돌면서, 포함된 방문만 `order`를 1부터 매긴다. 사진은 `선택됨 && 그 카드에 실려 있음`(= CANDIDATE·CONFIRMED)만 넣는다.
- [ ] **Step 4: 통과 확인** → PASS
- [ ] **Step 5: Commit** — `git commit -m "feat: share draft builder"`

### Task 7: 공유 선택·미리보기 화면 (F8, F9 UI)

**Files:**
- Create: `ui/ShareSelectScreen.kt`, `ui/SharePreviewScreen.kt`, `ui/ShareViewModel.kt`
- Modify: `ui/DayDraftScreen.kt`('이날 공유하기' 버튼), `MainActivity.kt`(라우트)

**Interfaces:**
- Consumes: `ShareDraftBuilder.build`, `MemoryRepository.observeDay`
- Produces: `class ShareViewModel`: `val selection: StateFlow<ShareSelection>`, `val draft: StateFlow<ShareDraft?>`, `fun toggleVisit(id: String)`, `fun togglePhoto(uri: String)`, `fun setCaption(visitId: String, text: String)`. 기본값은 모든 방문 포함, 모든 사진 선택, 설명 없음. Activity 범위로 만들어 주행 화면에 다녀와도 선택이 남는다.

- [ ] **Step 1: 선택 화면** — 방문마다 포함 스위치, 사진 체크, 설명 입력칸을 두고, 하단에 '미리보기' 버튼을 둔다. `draft == null`이면 버튼을 비활성화한다.
- [ ] **Step 2: 미리보기 화면** — `ShareDraft`만으로 그린다(원본 엔티티 참조 금지). 상단에 "공유되는 내용만 보여요" 한 줄을 두고, 하단에 '이미지로 내보내기' 버튼을 둔다(Task 8에서 연결).
- [ ] **Step 3: 수동 확인** — 카페 제외, p3만 해제, 해변 설명 "바다에서 한참 놀았어요" 입력 → Expected: 미리보기에 칼국수집·해변 두 곳만 있고 p3이 없으며, 음성 메모 문구가 어디에도 없다.
- [ ] **Step 4: Commit** — `git commit -m "feat: share selection and preview screens"`

### Task 8: 이미지 내보내기 (F10)

**Files:**
- Create: `platform/ShareImage.kt`
- Test: `test/.../platform/ShareImageTextTest.kt`

**Interfaces:**
- Consumes: `ShareDraft`
- Produces:
  - `object ShareImageText { fun lines(draft: ShareDraft): List<String> }`
  - `class ShareImageRenderer(context: Context) { fun render(draft: ShareDraft, widthPx: Int = 1080): Bitmap }`
  - `class ShareExporter(context: Context) { suspend fun saveToGallery(bitmap: Bitmap, displayName: String): Uri; fun shareIntent(uri: Uri): Intent }`

- [ ] **Step 1: 실패하는 테스트**

```kotlin
@Test fun lines_haveDateThenNumberedPlacesThenCaptions() {
    val draft = ShareDraft("2026-11-08", listOf(
        ShareItem(1, "대부도 칼국수집", listOf("content://p1"), null),
        ShareItem(2, "방아머리해변", listOf("content://p2"), "바다에서 한참 놀았어요")))
    assertEquals(listOf("2026년 11월 8일", "1. 대부도 칼국수집", "2. 방아머리해변", "바다에서 한참 놀았어요"), ShareImageText.lines(draft))
}
```

- [ ] **Step 2: 실패 확인** → FAIL
- [ ] **Step 3: 구현** — `lines`는 날짜 한 줄 다음에 방문마다 `"{order}. {placeName}"`과, caption이 있으면 그 줄을 붙인다. `render`는 세로 캔버스에 `lines` 순서대로 글을 쓰고, 각 방문 줄 아래에 그 방문의 사진을 폭에 맞춰(다운샘플해서) 그린다. 맨 아래에 "기억 저장소"를 작게 넣는다.
- [ ] **Step 4: 통과 확인** → PASS
- [ ] **Step 5: 내보내기 연결** — `saveToGallery`는 `MediaStore.Images`의 `Pictures/기억저장소/`에 PNG로 저장한다. 저장 후 `Intent.createChooser(shareIntent(uri), null)`를 띄운다.
  Run: 미리보기 → 내보내기 → Expected: 갤러리에 이미지가 생기고, 열어 보면 카페·개인 메모·좌표가 없다.
- [ ] **Step 6: Commit** — `git commit -m "feat: export share draft as image"`

### Task 9: 주행 상태 게이트 (F1)

**Files:**
- Create: `domain/RouteGate.kt`, `platform/DriveStateSource.kt`, `ui/DrivingScreen.kt`
- Modify: `MainActivity.kt`, `AndroidManifest.xml`
- Test: `test/.../domain/RouteGateTest.kt`

**Interfaces:**
- Produces:
  - `enum class DriveState { DRIVING, PARKED }`, `enum class Route { Driving, DayDraft, ShareSelect, SharePreview }`
  - `object RouteGate { fun routeFor(state: DriveState, requested: Route): Route }`
  - `interface DriveStateSource { val state: StateFlow<DriveState> }`, `class UxRestrictionsDriveStateSource(context: Context) : DriveStateSource`

- [ ] **Step 1: 실패하는 테스트**

```kotlin
@Test fun driving_overridesEveryRoute() = Route.values().forEach { assertEquals(Route.Driving, RouteGate.routeFor(DriveState.DRIVING, it)) }
@Test fun parkedOnDrivingRoute_goesToDayDraft() = assertEquals(Route.DayDraft, RouteGate.routeFor(DriveState.PARKED, Route.Driving))
@Test fun parked_keepsRequestedRoute() = assertEquals(Route.ShareSelect, RouteGate.routeFor(DriveState.PARKED, Route.ShareSelect))
```

- [ ] **Step 2: 실패 확인** → FAIL
- [ ] **Step 3: 구현** — 위 세 규칙 그대로 구현한다.
- [ ] **Step 4: 통과 확인** → PASS
- [ ] **Step 5: 플랫폼 연결** — `UxRestrictionsDriveStateSource`는 `Car.createCar(context)` → `CarUxRestrictionsManager`를 받아, `isRequiresDistractionOptimization`이 true이면 DRIVING, 아니면 PARKED를 낸다. 리스너로 변화를 반영한다. 이 방식이 에뮬레이터에서 안 되면, 같은 인터페이스로 Vehicle SDK Brake 도메인의 주차 브레이크 상태를 읽는 구현체로 바꾼다. `MainActivity`는 `state`를 관찰해 현재 라우트를 `RouteGate.routeFor`로 덮어쓴다. 주행 중에도 화면이 가려지지 않도록 액티비티에 `<meta-data android:name="distractionOptimized" android:value="true"/>`를 넣는다. `DrivingScreen`은 화면 중앙의 큰 '한마디 남기기' 버튼 하나만 둔다(동작은 Task 11에서 연결).
- [ ] **Step 6: 수동 확인** — 공유 선택 화면에서 에뮬레이터 기어를 D로 바꾼다 → 주행 화면으로 바뀌어야 한다. 다시 P로 바꾼다 → 하루 초안으로 돌아오고, 공유 선택을 다시 열면 이전 선택이 남아 있어야 한다.
- [ ] **Step 7: Commit** — `git commit -m "feat: lock editing while driving"`

**Day 2 관문:** P0 공유 흐름 완성. 못 넘으면 Task 11은 텍스트 입력 대체로 줄이고 Day 3를 Task 12에 쓴다.

---

## Day 3 · 11/15(일) — 관문: 21시 제출

### Task 10: (예비) Day 2에서 넘어온 작업

Day 2 태스크 중 남은 것을 먼저 끝낸다. 남은 게 없으면 건너뛴다.

### Task 11: 음성 메모 (F2)

**Files:**
- Create: `domain/VoiceMemo.kt`, `platform/SpeechInput.kt`
- Modify: `ui/DrivingScreen.kt`, `MainActivity.kt`, `AndroidManifest.xml`(`RECORD_AUDIO`)
- Test: `test/.../domain/VoiceMemoTest.kt`

**Interfaces:**
- Consumes: `Visit`, `NoteKind`, `MemoryRepository.addNote`, `SeedDay.demoNow`
- Produces:
  - `sealed interface SttResult { data class Text(val value: String) : SttResult; data class Error(val reason: String) : SttResult }`
  - `fun interface SpeechInput { suspend fun listenOnce(): SttResult }`, `class PleosSpeechInput(context: Context) : SpeechInput`, `class AndroidSpeechInput(context: Context) : SpeechInput` (ko-KR)
  - `fun targetVisit(visits: List<Visit>, now: Long): Visit?` (arrivedAt ≤ now인 방문 중 가장 늦은 것)
  - `sealed interface VoiceMemoOutcome { data class Saved(val visitId: String) : VoiceMemoOutcome; data object NothingHeard : VoiceMemoOutcome; data object NoVisit : VoiceMemoOutcome; data object Failed : VoiceMemoOutcome }`
  - `class VoiceMemoUseCase(private val visits: suspend () -> List<Visit>, private val addNote: suspend (visitId: String, text: String) -> Boolean, private val now: () -> Long) { suspend fun record(input: SpeechInput): VoiceMemoOutcome }`

- [ ] **Step 1: 실패하는 테스트**

```kotlin
@Test fun afterLeavingBeach_targetsBeach() = assertEquals("v2", targetVisit(day, t("14:58"))?.id)
@Test fun duringCafe_targetsCafe() = assertEquals("v3", targetVisit(day, t("15:30"))?.id)
@Test fun beforeFirstVisit_targetsNothing() = assertNull(targetVisit(day, t("11:00")))
@Test fun text_isSavedVerbatimOnTarget() = runTest {
    val saved = mutableListOf<Pair<String, String>>()
    val uc = VoiceMemoUseCase({ day }, { v, s -> saved += v to s; true }, { t("14:58") })
    assertEquals(VoiceMemoOutcome.Saved("v2"), uc.record { SttResult.Text("애들이 바다에서 한참 놀았네") })
    assertEquals(listOf("v2" to "애들이 바다에서 한참 놀았네"), saved)
}
@Test fun blankText_savesNothing() = runTest { /* Text("  ") → NothingHeard, saved 비어 있음 */ }
@Test fun sttError_savesNothing() = runTest { /* Error("no match") → Failed, saved 비어 있음 */ }
@Test fun noVisitYet_savesNothing() = runTest { /* now = t("11:00") → NoVisit */ }
```

(주석 처리한 세 테스트는 첫 테스트와 같은 구조로, 표시한 입력·결과만 바꾼다.)

- [ ] **Step 2: 실패 확인** → FAIL
- [ ] **Step 3: 구현** — `record`는 `listenOnce` → Error면 Failed, 공백이면 NothingHeard → `targetVisit`가 null이면 NoVisit → `addNote(visit.id, text)`의 결과로 Saved를 돌려준다. `addNote` 람다는 `MainActivity`에서 `repo.addNote(id, text, NoteKind.VOICE, now())`로 묶는다. `now`에는 데모 빌드에서 `seed.demoNow`를 넣는다.
- [ ] **Step 4: 통과 확인** → PASS
- [ ] **Step 5: STT 연결(12시 컷)** — Pleos SpeechToText SDK 샘플을 참고해 `PleosSpeechInput`을 구현한다. 12시까지 에뮬레이터 마이크로 인식이 안 되면 `AndroidSpeechInput`(`SpeechRecognizer`, `RecognizerIntent.EXTRA_LANGUAGE = "ko-KR"`)로 바꾼다. 그것도 안 되면 주차 상태의 텍스트 입력으로 대체하고 PRD 결정 표를 고친다. `DrivingScreen` 버튼은 결과에 따라 "해변에 남겼어요" / "다시 말씀해 주세요" / "아직 방문 기록이 없어요" 중 하나를 2초간 보여준다.
- [ ] **Step 6: 수동 확인** — 기어 D → 버튼 → "애들이 바다에서 한참 놀았네" 발화 → 기어 P → Expected: 해변 카드에 마이크 아이콘 메모가 원문 그대로 있다.
- [ ] **Step 7: Commit** — `git commit -m "feat: voice memo while driving"`

### Task 12: 시연 준비·제출물

**Files:**
- Create: `samples/p1.jpg`~`p7.jpg`, `README.md`

- [ ] **Step 1: 시연 사진 EXIF 설정·주입** — 직접 고른 사진 7장에 exiftool로 시각·위치를 넣는다.

| 파일 | 내용 | DateTimeOriginal | 위치 | 기대 결과 |
| --- | --- | --- | --- | --- |
| p1 | 칼국수 | 2026:11:08 12:25:00 | 37.2831, 126.5811 | 칼국수집 후보 |
| p2·p3·p4 | 해변·아이 | 13:45 / 14:10 / 14:30 | 37.2846, 126.5779 | 해변 후보 |
| p5 | 카페 음료 | 15:20 | 37.2790, 126.5850 | 카페 후보 |
| p6 | 영수증 | 14:12 | 37.2846, 126.5779 | 해변 후보 → 시연에서 해제 |
| p7 | 아무 사진 | (없음, `exiftool -all=`) | 없음 | 어디에도 안 붙음 |

```bash
exiftool -DateTimeOriginal="2026:11:08 13:45:00" -GPSLatitude=37.2846 -GPSLatitudeRef=N -GPSLongitude=126.5779 -GPSLongitudeRef=E samples/p2.jpg
adb push samples/. /sdcard/Pictures/
adb shell content call --method scan_volume --uri content://media --arg external_primary
```

- [ ] **Step 2: PRD 검증 체크리스트 8개 실행** — PRD '검증과 시연 영상'의 체크 항목을 하나씩 확인하고 PRD에서 체크한다.
- [ ] **Step 3: README** — 실행 환경(Pleos Connect Emulator, cold boot, 마이크 ON), 사진 주입 명령, 데모 흐름 7단계, "방문은 시드 데이터, 실제 감지는 범위 밖"을 적는다. 실명·연락처·계정 정보는 넣지 않는다(지원서 안내).
- [ ] **Step 4: APK·영상** — `./gradlew assembleDebug`로 APK를 만든다. PRD 영상 구성대로 촬영하고, 3단계에 "휴대폰에서 가져온 사진" 자막을 넣는다.
- [ ] **Step 5: Commit & 제출** — `git commit -m "docs: demo readme and samples"` → 21시 제출 메일 발송

---

## 시간이 남을 때 (P1 → C 순서, 각각 독립)

### Task 13 (P1): 정보 출처 배지(F11) + 사진 옮기기(F12)

- Produces: `suspend fun MemoryRepository.movePhoto(uri: String, toVisitId: String)` (상태는 CONFIRMED로 바꿈)
- [ ] 실패하는 테스트 `movePhoto_reassignsAndConfirms`(androidTest): p6을 v1로 옮기면 `visitId == "v1"`, `linkState == CONFIRMED` → 실패 확인 → 구현 → 통과 → Commit
- [ ] UI: 방문 카드에 '확인됨', 메모에 '내가 남긴 말', 후보 사진에 '후보' 배지를 단다. 길게 누르기 메뉴에 '다른 방문으로'를 추가한다. 수동 확인 → Commit

### Task 14 (P1): 음성 메모 다듬기(F13)

- Produces: `fun interface Refiner { suspend fun refine(original: String): String? }`, `object RefinePrompt { val SYSTEM: String }`
- `SYSTEM`(그대로 사용): "사용자가 차 안에서 남긴 말을 읽기 쉬운 한 문장으로 다듬어라. 원문에 없는 감정, 사실, 사람, 장소를 추가하지 마라. 다듬을 것이 없으면 원문을 그대로 돌려줘라."
- [ ] 실패하는 테스트 `refinerFailure_keepsOriginalOnly`: Refiner가 null을 반환하면 `Note.refinedText`는 null이고 `text`는 변하지 않는다 → 구현(Pleos LLM SDK 사용, 실패·타임아웃 시 null) → 통과 → Commit
- [ ] UI: 다듬은 문장은 원문 아래에 회색으로 보여준다. 수동 확인 → Commit

### Task 15 (C): 3D 기억 맵

P0가 모두 끝났고 11/15 12시 전일 때만 시작한다. 시작하면 PRD 스트레치 절을 스펙으로 삼아 계획을 따로 쓴다.
