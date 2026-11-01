# 기존 11개 API 계약 변경 보고

## 기준과 검증 범위

비교 자료는 `/Users/seohyunjin/ideaProjects/Deposit_Termination_System_channel 2`의 소스와 변경 내역이다.
원본 `swagger.html`, `README(4).html`, `channel_api_roles(1).md`가 없어 OpenAPI와의 완전한 일치는 확정하지 않았다.
사용자가 제공한 요청 JSON 및 필드 목록과 비교 소스에서 확인한 변경을 적용했다.
기존 11개 API의 메서드·경로를 유지했다. inquire/cancel/mobile API와 모바일 테이블은 추가하지 않았다.

## API별 변경

| API | 변경 내용 | 주요 파일·메서드 |
|---|---|---|
| GET /branch/deposits/{no} | AQ001과 기존 응답 유지. productCode 삭제 여부는 원본 명세 확인 필요 | TerminationService.getDeposit |
| POST /branch/terminations | 확장된 해지 응답과 중첩 payout, 기준금리 저장. 201 및 self 기반 Location 유지 | TerminationService.create/toResponse |
| GET /branch/terminations | items 구조 유지, 개별 응답 변경 적용 | TerminationController.getAll, TerminationService.toResponse |
| GET /branch/terminations/{id} | channelTxId, isAgent, passbookPresented, payout, approvalId, failCode, updatedAt 및 quote.baseRate | TerminationService.get/toResponse |
| POST /branch/terminations/{id}/execute | JSON tellerId 필수·양수 검증, 접수 직원 일치 확인. 승인·중복 방지·오류 분류·UPDATE 검증 유지 | TerminationController.execute, TerminationService.execute |
| GET /branch/terminations/{id}/receipt | elapsedDays, appliedRate, payout, tellerId, approverId 및 링크. 비교 소스의 승인 건 ID 오매핑을 승인 직원 ID 조회로 수정 | TerminationService.getReceipt |
| POST /branch/terminations/{id}/approvals | 헤더 대신 JSON requestedBy. 접수 직원 검증 유지, 201 유지 | ApprovalController.create, ApprovalService.create |
| GET /branch/approvals | 배열을 items 객체로 변경, 각 항목 응답 확장 | ApprovalController.list, ApprovalService.toResponse |
| GET /branch/approvals/{approvalId} | approvalId 이름, 고객명·예금계좌·링크 추가 | ApprovalService.detail/toResponse |
| POST /branch/approvals/{approvalId}/approve | JSON approverId/comment 사용, comment를 decision_note에 저장. 승인 규칙 유지 | ApprovalController.approve, ApprovalService.approve/decide |
| POST /branch/approvals/{approvalId}/reject | JSON approverId/reason 사용, 공백 사유 거절, reason을 decision_note에 저장 | ApprovalController.reject, ApprovalService.reject/decide |

## DTO 변경

- 추가: `TerminationExecuteRequest(tellerId)`, `PayoutResponse(method, accountNo)`.
- 승인 요청: requestedBy/approverId 추가, 승인 decisionNote → comment, 반려 decisionNote → reason.
- TerminationResponse: channelTxId/isAgent/passbookPresented/approvalId/updatedAt 추가, payoutMethod/payoutAccountNo를 payout으로 이동, failReason → failCode.
- TerminationResponse에서 customerId, actualPrincipal/actualInterest/actualTax/actualPayout, completedAt 제거. 실제 계산 결과는 기존 DB 저장을 유지하며 계산서에서 제공.
- TerminationQuoteResponse: baseRate 추가.
- TerminationReceiptResponse: elapsedDays/appliedRate/tellerId/approverId/_links 추가, payout 중첩 객체 사용.
- ApprovalResponse: id → approvalId, customerName/depositAccountNo/_links 추가. 기존 decisionNote 응답 필드는 비교 소스대로 유지.
- 공통 오류: code/message에 path/timestamp 추가. 잘못된 경로 변수·상태 enum도 동일한 400 오류 형식으로 처리.
- TerminationCreateRequest의 구조는 비교 소스와 같아 변경하지 않음.

## 수정 파일

`src/main/java/deposit_early_termination_channel/` 아래:

- controller: ApprovalController, TerminationController
- dto/request: ApprovalCreateRequest, ApprovalDecisionRequest, ApprovalRejectRequest, TerminationExecuteRequest
- dto/response: ApprovalResponse, PayoutResponse, TerminationQuoteResponse, TerminationReceiptResponse, TerminationResponse
- domain/model: TerminationQuote
- repository: TerminationQuoteRepository.save/findByTerminationId
- service: ApprovalService와 TerminationService의 상기 메서드
- exception: ApprovalExceptionHandler

테스트:
- src/test/java/deposit_early_termination_channel/ChannelApplicationTests.java
- src/test/java/deposit_early_termination_channel/service/TerminationServiceTest.java
- src/test/java/deposit_early_termination_channel/service/ApprovalServiceTest.java

기존 사용자 변경인 MciClient·application.properties·WebConfig 설정은 이번 작업에서 변경하지 않았다.

## DB 변경 — 서버 실행 전 수동 반영 필요

`db/migration/V2__add_quote_base_rate.sql`은 수동 SQL이다. 자동 Flyway 실행을 추가하지 않았고 실제 DB에도 실행하지 않았다.

기준금리를 접수 시점의 값으로 보존하려면 기존 quote 테이블에 저장이 필요하므로 `termination_quote.base_rate DECIMAL(12,4) NULL` 컬럼을 추가한다.
기존 컬럼의 applied_rate는 중도해지 적용금리이므로 기준금리로 대체하지 않는다. 별도 테이블은 만들지 않는다.
이미 컬럼이 존재하는 DB에는 이 ALTER를 중복 실행하지 않아야 한다.
기존 행은 NULL로 남으며 당시 기준금리의 신뢰할 수 있는 자료가 있을 때만 별도 보정한다.
마이그레이션 미적용 시 새 Repository의 INSERT/SELECT가 실패할 수 있다.
실제 DDL이 없어 컬럼 존재·정밀도·updated_at 자동 갱신 여부 등은 실제 DB에서 확인이 필요하다.

## 보존 동작 및 남은 명세 확인

- AQ001/DQ001/DT001, 승인 조건과 전결 한도·본인 승인 금지·지점 검증을 유지했다.
- 실행의 연결 거부 → FAILED, 타임아웃/불명확한 결과 → UNKNOWN, UNKNOWN 재실행 차단을 유지했다.
- finishWithError와 complete의 갱신 1건 검증을 유지했다.
- 기존 실행 API는 COMPLETED/연결 거부 FAILED에 200, UNKNOWN/PROCESSING에 202, 명시적 업무 FAIL에 업무 오류 HTTP 상태를 반환한다. 원본 Swagger와 202 충돌 여부는 확정하지 못했으므로 기존 동작을 보존했다.
- 링크는 공개 URL 설정을 사용하고 구현된 11개 API만 참조한다. cancel/inquire 링크는 추가하지 않았다.
- OTHER_BANK와 productCode 삭제 여부, 응답 필드 required/null 허용, 승인 decisionNote 응답명, 정확한 상태별 링크 집합은 원본 Swagger·기획서 확인 필요.
- 날짜는 기존 Java LocalDate/LocalDateTime 직렬화를 유지한다. 원본 명세의 시간대·소수 초 요구는 확인되지 않았다.
- 계산서 elapsedDays/appliedRate는 기존 저장 견적을 사용한다. DT001 응답과 DB에 실행 시점의 별도 일수·금리가 없으므로 다른 시점의 값이 요구되는지는 계약 확인이 필요하다.
- JSON 직원 ID는 사용자 식별 값이며 인증 체계를 새로 도입하지 않았다.

## 테스트와 빌드

실제 MCI·DB를 사용하는 SpringBootTest 대신 MockMvc standalone 및 Mockito 서비스 테스트를 사용한다.
11개 API 경로, JSON 직원 ID 전달, 응답 필드·날짜·Location, 오류 필드, 반려 사유 검증, 승인 규칙,
FAILED/UNKNOWN, UNKNOWN 중복 실행 차단, UPDATE 실패, 승인 ID 전달, 기준금리 저장, 계산서 승인 직원 ID 및 링크를 검증한다.

실행: `bash gradlew --offline test build`
결과: 테스트 30개 성공, 실패·오류 0개. Gradle BUILD SUCCESSFUL.
실제 DB 마이그레이션 및 MCI/core/React 연동 검증은 수행하지 않았다.
