package deposit_early_termination_channel;

import deposit_early_termination_channel.domain.enums.ApprovalReason;
import deposit_early_termination_channel.domain.enums.ApprovalStatus;
import deposit_early_termination_channel.domain.enums.TerminationStatus;
import deposit_early_termination_channel.domain.model.Approval;
import deposit_early_termination_channel.domain.model.Teller;
import deposit_early_termination_channel.dto.request.ApprovalCreateRequest;
import deposit_early_termination_channel.dto.response.ApprovalResponse;
import deposit_early_termination_channel.exception.BusinessException;
import deposit_early_termination_channel.repository.ApprovalRepository;
import deposit_early_termination_channel.repository.ApprovalTellerRepository;
import deposit_early_termination_channel.repository.TerminationApprovalRepository;
import deposit_early_termination_channel.repository.TerminationApprovalRepository.TerminationApprovalView;
import deposit_early_termination_channel.service.ApprovalService;

import deposit_early_termination_channel.domain.enums.TellerRole;
import deposit_early_termination_channel.dto.request.ApprovalDecisionRequest;
import deposit_early_termination_channel.dto.request.ApprovalRejectRequest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class ApprovalServiceTest {

    @Mock
    private ApprovalRepository approvals;

    @Mock
    private ApprovalTellerRepository tellers;

    @Mock
    private TerminationApprovalRepository terminations;

    @InjectMocks
    private ApprovalService service;

    /* 승인 요청 테스트 */
    // 접수 직원이 승인 요청 가능(본인, RECEIVED, 승인 사유 있음) -> 생성된 승인 정보 반환
    @Test
    void create_requestOwner_createsApprovalAndChangesTerminationStatus() {
        // Given
        Long terminationId = 100L;
        Long actorId = 1L;
        Long approvalId = 200L;
        Long payoutAmount = 10_000_000L;
        List<ApprovalReason> reasons = List.of(ApprovalReason.HIGH_AMOUNT);
        ApprovalCreateRequest request = new ApprovalCreateRequest("고액 지급 승인 요청");
        LocalDateTime requestedAt = LocalDateTime.of(2026, 10, 29, 10, 0);
        Approval saved = Approval.builder()
                .id(approvalId)
                .terminationId(terminationId)
                .reasons(reasons)
                .payoutAmount(payoutAmount)
                .requestedBy(actorId)
                .memo(request.memo())
                .requestedAt(requestedAt)
                .status(ApprovalStatus.PENDING)
                .build();

        when(terminations.lockTermination(terminationId)).thenReturn(
                new TerminationApprovalView(terminationId, actorId, TerminationStatus.RECEIVED, reasons));
        when(tellers.findById(actorId)).thenReturn(Teller.builder().id(actorId).build());
        when(terminations.findQuotePayout(terminationId)).thenReturn(payoutAmount);
        when(approvals.insert(terminationId, reasons, payoutAmount, actorId, request.memo()))
                .thenReturn(approvalId);
        when(terminations.updateStatus(terminationId, TerminationStatus.RECEIVED,
                TerminationStatus.WAITING_APPROVAL)).thenReturn(1);
        when(approvals.findById(approvalId)).thenReturn(saved);

        // When
        ApprovalResponse response = service.create(terminationId, actorId, request);

        // Then
        assertEquals(approvalId, response.id());
        assertEquals(terminationId, response.terminationId());
        assertEquals(reasons, response.reasons());
        assertEquals(payoutAmount, response.payoutAmount());
        assertEquals(actorId, response.requestedBy());
        assertEquals(request.memo(), response.memo());
        assertEquals(requestedAt, response.requestedAt());
        assertEquals(ApprovalStatus.PENDING, response.status());
        verify(approvals).insert(terminationId, reasons, payoutAmount, actorId, request.memo());
        verify(terminations).updateStatus(terminationId, TerminationStatus.RECEIVED,
                TerminationStatus.WAITING_APPROVAL);
    }

    // 다른 직원은 요청 불가(접수 직원과 요청 직원 다름) -> NOT_REQUEST_OWNER 반환
    @Test
    void create_otherTeller_throwsNotRequestOwnerWithoutSaving() {
        // Given
        Long terminationId = 100L;
        Long ownerId = 1L;
        Long actorId = 2L;
        ApprovalCreateRequest request = new ApprovalCreateRequest("승인 요청");

        when(terminations.lockTermination(terminationId)).thenReturn(
                new TerminationApprovalView(terminationId, ownerId, TerminationStatus.RECEIVED,
                        List.of(ApprovalReason.HIGH_AMOUNT)));
        when(tellers.findById(actorId)).thenReturn(Teller.builder().id(actorId).build());

        // When
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.create(terminationId, actorId, request));

        // Then
        assertEquals("NOT_REQUEST_OWNER", exception.getCode());
        verifyNoInteractions(approvals);
        verify(terminations, never()).findQuotePayout(anyLong());
        verify(terminations, never()).updateStatus(anyLong(), any(), any());
    }


    private static final Long TID = 100L, AID = 200L, OWNER = 1L, ACTOR = 2L;
    private static final List<ApprovalReason> REASONS =
            List.of(ApprovalReason.HIGH_AMOUNT, ApprovalReason.NO_PASSBOOK);
    private static final String NOTE = "처리 의견";
    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 29, 10, 0);

    // C01: 복수 승인 사유와 조회한 견적 금액을 저장한다.
    @Test
    void create_multipleReasons_preservesFields() {
        // Given
        createContext(TerminationStatus.RECEIVED, REASONS);
        when(terminations.findQuotePayout(TID)).thenReturn(100L);
        when(approvals.insert(TID, REASONS, 100L, OWNER, NOTE)).thenReturn(AID);
        when(terminations.updateStatus(TID, TerminationStatus.RECEIVED,
                TerminationStatus.WAITING_APPROVAL)).thenReturn(1);
        Approval saved = approval(ApprovalStatus.PENDING, 100L);
        when(approvals.findById(AID)).thenReturn(saved);

        // When
        ApprovalResponse result = service.create(TID, OWNER, new ApprovalCreateRequest(NOTE));

        // Then
        assertResponse(saved, result);
        verify(approvals).insert(TID, REASONS, 100L, OWNER, NOTE);
        verify(terminations).updateStatus(TID, TerminationStatus.RECEIVED, TerminationStatus.WAITING_APPROVAL);
    }

    // C03
    @ParameterizedTest
    @EnumSource(value = TerminationStatus.class, names = "RECEIVED", mode = EnumSource.Mode.EXCLUDE)
    void create_notReceived_rejectsWithoutWriting(TerminationStatus status) {
        // Given
        createContext(status, REASONS);
        // When
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(TID, OWNER, new ApprovalCreateRequest(NOTE)));
        // Then
        assertEquals("INVALID_TERMINATION_STATUS", ex.getCode());
        noCreateWrites();
    }

    // C04
    @Test
    void create_noReasons_rejectsWithoutWriting() {
        // Given
        createContext(TerminationStatus.RECEIVED, List.of());
        // When
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(TID, OWNER, new ApprovalCreateRequest(NOTE)));
        // Then
        assertEquals("APPROVAL_NOT_REQUIRED", ex.getCode());
        noCreateWrites();
    }

    // C05-C08: 예외 발생 위치 이후의 호출을 중단한다.
    @ParameterizedTest
    @ValueSource(strings = {"TERMINATION", "TELLER", "QUOTE", "INSERT"})
    void create_dependencyFailure_propagatesAndStops(String stage) {
        // Given
        BusinessException failure = failure(stage);
        stubCreateFailure(stage, failure);
        // When
        BusinessException actual = assertThrows(BusinessException.class,
                () -> service.create(TID, OWNER, new ApprovalCreateRequest(NOTE)));
        // Then
        assertSame(failure, actual);
        verify(terminations, never()).updateStatus(anyLong(), any(), any());
        verify(approvals, never()).findById(anyLong());
        if (!stage.equals("INSERT")) verifyNoInteractions(approvals);
        if (stage.equals("TERMINATION")) verifyNoInteractions(tellers);
        if (stage.equals("TERMINATION") || stage.equals("TELLER"))
            verify(terminations, never()).findQuotePayout(anyLong());
    }

    // C09: 앞선 insert는 호출된다. 실제 롤백은 통합 테스트 범위이다.
    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void create_statusUpdateNotOne_throwsConflict(int rows) {
        // Given
        createContext(TerminationStatus.RECEIVED, REASONS);
        when(terminations.findQuotePayout(TID)).thenReturn(100L);
        when(approvals.insert(TID, REASONS, 100L, OWNER, NOTE)).thenReturn(AID);
        when(terminations.updateStatus(TID, TerminationStatus.RECEIVED,
                TerminationStatus.WAITING_APPROVAL)).thenReturn(rows);
        // When
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(TID, OWNER, new ApprovalCreateRequest(NOTE)));
        // Then
        assertEquals("CONCURRENT_MODIFICATION", ex.getCode());
        verify(approvals).insert(TID, REASONS, 100L, OWNER, NOTE);
        verify(approvals, never()).findById(anyLong());
    }

    /* 승인/반려 공통 사례: approve=true이면 approve(), false이면 reject()를 실제 호출한다. */

    // D01: 이미 처리된 요청을 다시 처리할 수 없다.
    @ParameterizedTest
    @CsvSource({"true, APPROVED", "true, REJECTED", "false, APPROVED", "false, REJECTED"})
    void decision_alreadyDecided_rejectsWithoutWriting(boolean approve, ApprovalStatus status) {
        // Given
        decisionContext(status, TerminationStatus.WAITING_APPROVAL, OWNER, 100L);
        // When
        BusinessException ex = assertThrows(BusinessException.class, () -> decide(approve, ACTOR));
        // Then
        assertEquals("INVALID_APPROVAL_STATUS", ex.getCode());
        noDecisionWrites();
        verifyNoInteractions(tellers);
    }

    // D02
    @ParameterizedTest
    @CsvSource({"true, RECEIVED", "true, APPROVED", "true, REJECTED", "true, PROCESSING",
            "true, COMPLETED", "true, FAILED", "true, UNKNOWN",
            "false, RECEIVED", "false, APPROVED", "false, REJECTED", "false, PROCESSING",
            "false, COMPLETED", "false, FAILED", "false, UNKNOWN"})
    void decision_terminationNotWaiting_rejectsWithoutWriting(boolean approve, TerminationStatus status) {
        // Given
        decisionContext(ApprovalStatus.PENDING, status, OWNER, 100L);
        // When
        BusinessException ex = assertThrows(BusinessException.class, () -> decide(approve, ACTOR));
        // Then
        assertEquals("INVALID_APPROVAL_STATUS", ex.getCode());
        noDecisionWrites();
        verifyNoInteractions(tellers);
    }

    // D03-D04: 요청자 본인, 접수자만 일치하는 경우를 각각 검증한다.
    @ParameterizedTest
    @CsvSource({"true, true", "true, false", "false, true", "false, false"})
    void decision_selfApproval_rejectsWithoutWriting(boolean approve, boolean requester) {
        // Given
        Long actor = requester ? OWNER : ACTOR;
        decisionContext(ApprovalStatus.PENDING, TerminationStatus.WAITING_APPROVAL,
                requester ? 3L : ACTOR, 100L);
        when(tellers.findById(OWNER)).thenReturn(teller(OWNER, TellerRole.MANAGER, 100L, "001"));
        if (!requester)
            when(tellers.findById(ACTOR)).thenReturn(teller(ACTOR, TellerRole.MANAGER, 100L, "001"));
        // When
        BusinessException ex = assertThrows(BusinessException.class, () -> decide(approve, actor));
        // Then
        assertEquals("SELF_APPROVAL", ex.getCode());
        noDecisionWrites();
    }

    // D05-D06, D09-D10: 타 지점, 일반 직원, 한도 초과, 한도 미설정
    @ParameterizedTest
    @CsvSource({"true, MANAGER, 100, 002, BRANCH_MISMATCH",
            "false, MANAGER, 100, 002, BRANCH_MISMATCH",
            "true, TELLER, 100, 001, NOT_AUTHORIZED",
            "false, TELLER, 100, 001, NOT_AUTHORIZED",
            "true, MANAGER, 99, 001, APPROVAL_LIMIT_EXCEEDED",
            "false, MANAGER, 99, 001, APPROVAL_LIMIT_EXCEEDED",
            "true, MANAGER, , 001, APPROVAL_LIMIT_EXCEEDED",
            "false, MANAGER, , 001, APPROVAL_LIMIT_EXCEEDED"})
    void decision_invalidAuthority_rejectsWithoutWriting(boolean approve, TellerRole role,
                                                          Long limit, String branch, String code) {
        // Given
        decisionContext(ApprovalStatus.PENDING, TerminationStatus.WAITING_APPROVAL, OWNER, 100L);
        decisionTellers(role, limit, branch);
        // When
        BusinessException ex = assertThrows(BusinessException.class, () -> decide(approve, ACTOR));
        // Then
        assertEquals(code, ex.getCode());
        noDecisionWrites();
    }

    // R01-R02, D07-D08, D11-D12: 한도 미만/동일, 지점장 한도 null/초과
    @ParameterizedTest
    @CsvSource({"true, MANAGER, 100, 99", "false, MANAGER, 100, 99",
            "true, MANAGER, 100, 100", "false, MANAGER, 100, 100",
            "true, BRANCH_MANAGER, , 100000000", "false, BRANCH_MANAGER, , 100000000",
            "true, BRANCH_MANAGER, 100, 101", "false, BRANCH_MANAGER, 100, 101"})
    void decision_authorized_savesDecisionAndReturnsResult(boolean approve, TellerRole role,
                                                            Long limit, Long payout) {
        // Given
        decisionContext(ApprovalStatus.PENDING, TerminationStatus.WAITING_APPROVAL, OWNER, payout);
        decisionTellers(role, limit, "001");
        ApprovalStatus decision = decisionStatus(approve);
        TerminationStatus next = nextStatus(approve);
        when(approvals.decide(AID, decision, ACTOR, NOTE)).thenReturn(1);
        when(terminations.updateStatus(TID, TerminationStatus.WAITING_APPROVAL, next)).thenReturn(1);
        Approval saved = approval(decision, payout);
        // 최초 조회와 최종 조회의 결과를 구분한다.
        when(approvals.findById(AID)).thenReturn(approval(ApprovalStatus.PENDING, payout), saved);
        // When
        ApprovalResponse result = decide(approve, ACTOR);
        // Then
        assertResponse(saved, result);
        verify(approvals).decide(AID, decision, ACTOR, NOTE);
        verify(terminations).updateStatus(TID, TerminationStatus.WAITING_APPROVAL, next);
        verify(approvals, times(2)).findById(AID);
    }

    // R03-R04: 갱신 건수는 정확히 1이어야 한다.
    @ParameterizedTest
    @CsvSource({"true, DECISION, 0", "true, DECISION, 2", "false, DECISION, 0", "false, DECISION, 2",
            "true, TERMINATION, 0", "true, TERMINATION, 2", "false, TERMINATION, 0", "false, TERMINATION, 2"})
    void decision_updateNotOne_throwsConflict(boolean approve, String stage, int rows) {
        // Given
        decisionEligible();
        when(approvals.decide(AID, decisionStatus(approve), ACTOR, NOTE))
                .thenReturn(stage.equals("DECISION") ? rows : 1);
        if (stage.equals("TERMINATION"))
            when(terminations.updateStatus(TID, TerminationStatus.WAITING_APPROVAL,
                    nextStatus(approve))).thenReturn(rows);
        // When
        BusinessException ex = assertThrows(BusinessException.class, () -> decide(approve, ACTOR));
        // Then
        assertEquals("CONCURRENT_MODIFICATION", ex.getCode());
        verify(approvals).decide(AID, decisionStatus(approve), ACTOR, NOTE);
        verify(approvals).findById(AID); // 최초 조회만 발생
        if (stage.equals("DECISION"))
            verify(terminations, never()).updateStatus(anyLong(), any(), any());
        else
            verify(terminations).updateStatus(TID, TerminationStatus.WAITING_APPROVAL, nextStatus(approve));
    }

    // E01-E05, R05
    @ParameterizedTest
    @CsvSource({"true, APPROVAL", "false, APPROVAL", "true, TERMINATION", "false, TERMINATION",
            "true, LOCKED_APPROVAL", "false, LOCKED_APPROVAL", "true, REQUESTER", "false, REQUESTER",
            "true, ACTOR", "false, ACTOR", "true, DECISION", "false, DECISION"})
    void decision_dependencyFailure_propagatesAndStops(boolean approve, String stage) {
        // Given
        BusinessException failure = failure(stage);
        stubDecisionFailure(stage, approve, failure);
        // When
        BusinessException actual = assertThrows(BusinessException.class, () -> decide(approve, ACTOR));
        // Then
        assertSame(failure, actual);
        verify(terminations, never()).updateStatus(anyLong(), any(), any());
        verify(approvals).findById(AID);
        if (!stage.equals("DECISION")) verify(approvals, never()).decide(anyLong(), any(), anyLong(), any());
        if (stage.equals("APPROVAL")) verifyNoInteractions(terminations, tellers);
        if (stage.equals("TERMINATION")) verify(approvals, never()).findByIdForUpdate(anyLong());
        if (List.of("TERMINATION", "LOCKED_APPROVAL").contains(stage)) verifyNoInteractions(tellers);
        if (stage.equals("REQUESTER")) verify(tellers, never()).findById(ACTOR);
    }

    /* 목록 및 상세 조회 */

    // Q01: 서로 다른 항목으로 변환 과정의 누락/중복을 검사한다.
    @ParameterizedTest
    @EnumSource(ApprovalStatus.class)
    void list_status_returnsMappedItemsWithoutWriting(ApprovalStatus status) {
        // Given
        Approval first = approval(status, 100L);
        Approval second = Approval.builder().id(201L).terminationId(101L)
                .reasons(List.of(ApprovalReason.AGENT)).payoutAmount(500L).requestedBy(3L)
                .memo("두 번째 요청").requestedAt(AT.plusHours(1)).status(status).build();
        when(approvals.findByStatus(status)).thenReturn(List.of(first, second));
        // When
        List<ApprovalResponse> result = service.list(status);
        // Then
        assertEquals(2, result.size());
        assertResponse(first, result.get(0));
        assertResponse(second, result.get(1));
        verify(approvals).findByStatus(status);
        verifyNoMoreInteractions(approvals);
        verifyNoInteractions(tellers, terminations);
    }

    // Q02
    @Test
    void list_noItems_returnsEmptyList() {
        // Given
        when(approvals.findByStatus(ApprovalStatus.PENDING)).thenReturn(List.of());
        // When
        List<ApprovalResponse> result = service.list(ApprovalStatus.PENDING);
        // Then
        assertTrue(result.isEmpty());
        verify(approvals).findByStatus(ApprovalStatus.PENDING);
        verifyNoMoreInteractions(approvals);
        verifyNoInteractions(tellers, terminations);
    }

    // Q03
    @Test
    void detail_existingApproval_returnsAllFieldsWithoutWriting() {
        // Given
        Approval saved = approval(ApprovalStatus.APPROVED, 100L);
        when(approvals.findById(AID)).thenReturn(saved);
        // When
        ApprovalResponse result = service.detail(AID);
        // Then
        assertResponse(saved, result);
        verify(approvals).findById(AID);
        verifyNoMoreInteractions(approvals);
        verifyNoInteractions(tellers, terminations);
    }

    // Q04
    @Test
    void detail_missingApproval_propagatesExceptionWithoutWriting() {
        // Given
        BusinessException failure = failure("APPROVAL_NOT_FOUND");
        when(approvals.findById(AID)).thenThrow(failure);
        // When
        BusinessException actual = assertThrows(BusinessException.class, () -> service.detail(AID));
        // Then
        assertSame(failure, actual);
        verify(approvals).findById(AID);
        verifyNoMoreInteractions(approvals);
        verifyNoInteractions(tellers, terminations);
    }

    /* 공통 준비/검증 도우미: 실제 DB 상태 변경이나 롤백을 흉내 내지 않는다. */

    private Teller teller(Long id, TellerRole role, Long limit, String branch) {
        return Teller.builder().id(id).role(role).approvalLimit(limit).branchNo(branch).build();
    }

    private TerminationApprovalView termination(Long owner, TerminationStatus status) {
        return new TerminationApprovalView(TID, owner, status, REASONS);
    }

    private Approval approval(ApprovalStatus status, Long payout) {
        boolean pending = status == ApprovalStatus.PENDING;
        return Approval.builder().id(AID).terminationId(TID).reasons(REASONS)
                .payoutAmount(payout).requestedBy(OWNER).memo(NOTE).requestedAt(AT)
                .status(status).approverId(pending ? null : ACTOR)
                .decisionNote(pending ? null : NOTE).decidedAt(pending ? null : AT.plusMinutes(10)).build();
    }

    private void createContext(TerminationStatus status, List<ApprovalReason> reasons) {
        when(terminations.lockTermination(TID)).thenReturn(new TerminationApprovalView(TID, OWNER, status, reasons));
        when(tellers.findById(OWNER)).thenReturn(teller(OWNER, TellerRole.TELLER, null, "001"));
    }

    private void noCreateWrites() {
        verifyNoInteractions(approvals);
        verify(terminations, never()).findQuotePayout(anyLong());
        verify(terminations, never()).updateStatus(anyLong(), any(), any());
    }

    private void decisionContext(ApprovalStatus status, TerminationStatus terminationStatus, Long owner, Long payout) {
        Approval current = approval(status, payout);
        when(approvals.findById(AID)).thenReturn(current);
        when(terminations.lockTermination(TID)).thenReturn(termination(owner, terminationStatus));
        when(approvals.findByIdForUpdate(AID)).thenReturn(current);
    }

    private void decisionTellers(TellerRole role, Long limit, String branch) {
        when(tellers.findById(OWNER)).thenReturn(teller(OWNER, TellerRole.TELLER, null, "001"));
        when(tellers.findById(ACTOR)).thenReturn(teller(ACTOR, role, limit, branch));
    }

    private void decisionEligible() {
        decisionContext(ApprovalStatus.PENDING, TerminationStatus.WAITING_APPROVAL, OWNER, 100L);
        decisionTellers(TellerRole.MANAGER, 100L, "001");
    }

    private ApprovalStatus decisionStatus(boolean approve) {
        return approve ? ApprovalStatus.APPROVED : ApprovalStatus.REJECTED;
    }

    private TerminationStatus nextStatus(boolean approve) {
        return approve ? TerminationStatus.APPROVED : TerminationStatus.REJECTED;
    }

    private ApprovalResponse decide(boolean approve, Long actor) {
        return approve
                ? service.approve(AID, actor, new ApprovalDecisionRequest(NOTE))
                : service.reject(AID, actor, new ApprovalRejectRequest(NOTE));
    }

    private void noDecisionWrites() {
        verify(approvals, never()).decide(anyLong(), any(), anyLong(), any());
        verify(terminations, never()).updateStatus(anyLong(), any(), any());
        verify(approvals).findById(AID);
    }

    private BusinessException failure(String code) {
        return new BusinessException(HttpStatus.CONFLICT, code, "테스트용 의존성 실패");
    }

    private void stubCreateFailure(String stage, BusinessException failure) {
        if (stage.equals("TERMINATION")) {
            when(terminations.lockTermination(TID)).thenThrow(failure);
            return;
        }
        when(terminations.lockTermination(TID)).thenReturn(termination(OWNER, TerminationStatus.RECEIVED));
        if (stage.equals("TELLER")) {
            when(tellers.findById(OWNER)).thenThrow(failure);
            return;
        }
        when(tellers.findById(OWNER)).thenReturn(teller(OWNER, TellerRole.TELLER, null, "001"));
        if (stage.equals("QUOTE")) {
            when(terminations.findQuotePayout(TID)).thenThrow(failure);
            return;
        }
        when(terminations.findQuotePayout(TID)).thenReturn(100L);
        when(approvals.insert(TID, REASONS, 100L, OWNER, NOTE)).thenThrow(failure);
    }

    private void stubDecisionFailure(String stage, boolean approve, BusinessException failure) {
        if (stage.equals("APPROVAL")) {
            when(approvals.findById(AID)).thenThrow(failure);
            return;
        }
        when(approvals.findById(AID)).thenReturn(approval(ApprovalStatus.PENDING, 100L));
        if (stage.equals("TERMINATION")) {
            when(terminations.lockTermination(TID)).thenThrow(failure);
            return;
        }
        when(terminations.lockTermination(TID)).thenReturn(termination(OWNER, TerminationStatus.WAITING_APPROVAL));
        if (stage.equals("LOCKED_APPROVAL")) {
            when(approvals.findByIdForUpdate(AID)).thenThrow(failure);
            return;
        }
        when(approvals.findByIdForUpdate(AID)).thenReturn(approval(ApprovalStatus.PENDING, 100L));
        if (stage.equals("REQUESTER")) {
            when(tellers.findById(OWNER)).thenThrow(failure);
            return;
        }
        when(tellers.findById(OWNER)).thenReturn(teller(OWNER, TellerRole.TELLER, null, "001"));
        if (stage.equals("ACTOR")) {
            when(tellers.findById(ACTOR)).thenThrow(failure);
            return;
        }
        when(tellers.findById(ACTOR)).thenReturn(teller(ACTOR, TellerRole.MANAGER, 100L, "001"));
        when(approvals.decide(AID, decisionStatus(approve), ACTOR, NOTE)).thenThrow(failure);
    }

    private void assertResponse(Approval expected, ApprovalResponse actual) {
        assertEquals(expected.getId(), actual.id());
        assertEquals(expected.getTerminationId(), actual.terminationId());
        assertEquals(expected.getReasons(), actual.reasons());
        assertEquals(expected.getPayoutAmount(), actual.payoutAmount());
        assertEquals(expected.getRequestedBy(), actual.requestedBy());
        assertEquals(expected.getMemo(), actual.memo());
        assertEquals(expected.getRequestedAt(), actual.requestedAt());
        assertEquals(expected.getStatus(), actual.status());
        assertEquals(expected.getApproverId(), actual.approverId());
        assertEquals(expected.getDecisionNote(), actual.decisionNote());
        assertEquals(expected.getDecidedAt(), actual.decidedAt());
    }
}
