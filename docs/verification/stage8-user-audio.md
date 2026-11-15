# 8단계 실제 사용자 녹음 — 로컬 STT 확인

> 과거 회차 기록이다. STT 누락 수정·실제 녹음 재확인과 소스 릴리즈의 최신 수락 범위는 [v0.1.0 안내](../releases/v0.1.0.md)를 우선한다. 아래 실패·보류 결과는 당시 관측으로 보존한다.

2026-11-15. 사용자가 인수인계 확인 후 “작업해”, “하고 확인받아”라고 지시했다. 총괄이 사용자 녹음 3개를 각각 1회 ON_DEVICE STT에 입력하고 결과를 보존했다. **저장 검사는 3/3 통과했으나 전체 문장 인식 및 8단계 수락은 보류한다.** 추가 진단·제품 수정·AI 연결 시험 전에 사용자 확인을 받는다.

## 관측 결과

| 녹음 | STT 결과 범위 — 제공된 대본과 비교 | 경험 날짜 해석 | 저장·재열기 |
| --- | --- | --- | --- |
| 1 | 첫 날짜 구절만 반환 | EXPLICIT_DATE / 2026-11-08 | 전체 PCM·WAV·인식 원문 동일 |
| 2 | 날짜와 첫 경험 문장까지, 뒤 문장은 반환되지 않음 | EXPLICIT_DATE / 2026-11-08 | 전체 PCM·WAV·인식 원문 동일 |
| 3 | 날짜와 첫 경험 문장까지, 대본에 없는 단어가 있고 뒤 문장은 반환되지 않음 | EXPLICIT_DATE / 2026-11-08 | 전체 PCM·WAV·인식 원문 동일 |

실제 인식 전문은 `.local/stage8-user-audio/results.md`와 각 `recording-0N-result.json`에만 보존한다. 대본을 인식 결과에 주입하거나 인식 원문을 고치지 않았다. root는 원음을 직접 청취하지 못했으므로 원음이 대본과 완전히 같다는 전제나 정확한 오인식률을 주장하지 않는다. 짧은 결과가 SDK의 발화 종료 판단 때문인지, 앱의 completed/첫 Final 처리 때문인지는 미확정이다. 현재 `AndroidPcmRecognitionEngine`은 completed 콜백을 Final로 전달하고 `PcmSpeechInput`은 첫 terminal만 전달한다. 이 코드 관측을 원인 확정으로 확대하지 않는다.

## 검증 근거와 실행 경계

- 브랜치 `feature/08-demo-rehearsal`, HEAD `cee5194fe3b34dbc4f1c0cf959969c61ac8fdc78`. 제품 코드는 변경하지 않았다.
- 실제 설치 제품 APK SHA256 `0c068687bf5651c30d70c5f5385a36375f18337712d698544827490ac3efcb8b`. 시험 전후 동일. 사용한 제품은 `07-memory-integration/app/build/outputs/apk/debug/app-debug.apk`와 일치한다. 루트의 다른 APK로 교체하지 않았다.
- 신규 시험: `app/src/androidTest/java/app/memoryvault/memory/Stage8UserAudioTest.kt`. `:app:assembleDebugAndroidTest` exit0, 35초. 시험 APK SHA256 `dd2817f570fb9ed84ca16acc6b442011dcb1549ce3c32e5bab0653cc1f31b65c`. `.local/stage8-user-audio/user-audio-androidTest.apk`에 고정했다.
- 전용 Pleos AVD, ADB 5038 / transport `127.0.0.1:5581`, 사용자10. 세 실행 모두 PARKED / READY. 각 JUnit `OK (1 test)`, instrumentation 정상 종료. 호스트 전체 경과 2.003 / 1.480 / 1.502초이며 음성 길이나 정확도 지표가 아니다.
- Mac `127.0.0.1:18763` 서버가 해당 회차 PCM 하나만 1회 제공하고 ADB reverse로 기기에 전달했다. 리다이렉트 금지·480000바이트 상한·입력 SHA 대조 후 실제 `PcmSpeechInput` → `AndroidPcmRecognitionEngine(ON_DEVICE)` → `PrivateCaptureStore` → `MemoryRepository`를 사용했다.
- 격리 Room DB와 앱 noBackup 아래 고유 배치로 저장했다. 음성 자동 연결 콜백은 no-op이므로 이 자료에 대한 AI 작업을 생성하지 않았다. production DB의 사용자 기록을 수정하지 않았다. 런처·AudioRecord·전체 UI 흐름 시험은 아니다.
- 기기 저장 PCM은 각각 338560 / 285312 / 330368바이트이며 입력과 전체 바이트 동일. 저장 WAV SHA도 기존 정규화 WAV와 각각 동일하다. DB close/reopen 및 새 capture store에서 Note·manifest·PCM·WAV를 대조했다. 테스트의 `PASS`는 이 저장 경로 검사 통과다.
- `capturedAt`은 2026-11-15 08:13:34 / 08:13:55 / 08:14:14 KST의 **시험 입력 시각**이며 원래 m4a 녹음 시각으로 주장하지 않는다. 경험 날짜는 실제 인식 문장과 이 시각에 기존 resolver를 적용한 결과다.
- 원본 사본·변환 파일의 manifest 해시와 WAV/PCM 일치를 재확인했다. 원음은 APK assets에 넣지 않았다. 시험 APK의 엔트리 이름 및 해시에서 사용자 원음 파일의 포함 여부를 검사했다. 이는 원격 게시용 전체 비밀정보 감사가 아니다.
- 실제 설치 제품 DEX의 SDK 로그 보호 검사: vendor log 호출0, SDK caller의 Android log 호출0, 결과 콜백1. 원문 로그 수집은 하지 않았다. 원시 instrumentation 결과는 로컬 권한0600으로 보존했다.
- 각 회차 종료 뒤 생성한 reverse만 제거하고 로컬 서버를 종료했다. 최종 reverse 목록 비어 있음, 18763 listener 없음. 마이크·외부 AI API·원음 클라우드 전송·제품 변경·push·Release·태그 없음.

원시 근거: `.local/stage8-user-audio/recording-0N-{instrumentation.log,status.json,result.json}`, `tested-evidence.json`, `.local/verification/stage8-user-audio-build-01.log`. source·APK·로그 해시를 묶어 보존했다. 기존 실패·합성 자료·다른 작업자의 변경은 유지했다.

## 확인 방법과 다음 경계

같은 Mac에서 `.local/stage8-user-audio/results.md`를 열면 실제 STT 전문을 확인할 수 있다. JSON에는 날짜·원음 해시·재열기 결과가 있다. 기기 원음은 앱 noBackup의 해당 배치에, Note는 배치별 격리 DB에 남아 있으며 일반 앱 목록에 섞지 않았다. 실행 하네스는 `.local/stage8-user-audio/run_recording.py`이며 기존 회차 로그가 있으면 재실행을 거절한다. 원음 전송·덮어쓰기·무작정 재시도를 하지 않는다.

다음 제안은 **문장 뒤쪽이 빠지는 현상의 로컬 원인 분리**다. SDK completed/종료 콜백과 앱의 첫 Final 종료 경계를 확인한 뒤 필요한 최소 수정안을 정한다. 원음 직접 청취 확인은 별도이며, 클라우드 STT 교체나 대본 기반 보정으로 우회하지 않는다. 사용자 확인 전 추가 시험·제품 수정·AI 연결 검증을 시작하지 않는다. 8단계·릴리즈 완료는 계속 보류한다.

Ruling: 이번 결과를 저장 경로 통과와 인식 품질 보류로 나누어 보고한다 — 세 날짜는 맞고 원음은 완전하지만 반환 문장이 대본보다 짧다 — 이를 전체 성공으로 보면 AI 연결에 필요한 내용 누락을 감추게 된다.

## 사용자 요청에 따른 1번 재확인

사용자가 1번 녹음 재확인을 명시적으로 요청해 같은 입력·제품/시험 APK로 추가1회 실행했다. 기존 결과는 보존하고 별도 배치 `.local/stage8-user-audio/recheck-01/`에 저장했다. **이전과 동일하게 첫 날짜 구절만 반환**했으며 경험 날짜2026-11-08, 전체PCM/WAV·인식 원문 저장/재열기는 다시 통과했다. JUnit1PASS, 추가 fetch1회, 호스트 경과1.457초. 기존 결과·소스·APK 해시 불변과 생성한 reverse 제거를 확인했다. 두 번 같은 결과였다는 관측이며 누락 원인은 여전히 미확정이다. 외부 API·제품 변경·다른 녹음 재시험은 하지 않았다.
