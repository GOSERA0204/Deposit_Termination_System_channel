package deposit_early_termination_channel.service;

import deposit_early_termination_channel.domain.enums.*;
import deposit_early_termination_channel.domain.model.*;
import deposit_early_termination_channel.dto.request.*;
import deposit_early_termination_channel.exception.BusinessException;
import deposit_early_termination_channel.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApprovalServiceTest {
    ApprovalRepository approvals;
    ApprovalTellerRepository tellers;
    TerminationApprovalRepository terminations;
    JdbcTemplate jdbc;
    ApprovalService service;
    @BeforeEach void setup() {
        approvals=mock(ApprovalRepository.class); tellers=mock(ApprovalTellerRepository.class);
        terminations=mock(TerminationApprovalRepository.class); jdbc=mock(JdbcTemplate.class);
        service=new ApprovalService(approvals,tellers,terminations,jdbc,"https://channel.example/");
    }
    Approval approval() {
        return Approval.builder().id(9L).terminationId(7L).requestedBy(1L).payoutAmount(10_000_000L)
                .reasons(List.of(ApprovalReason.HIGH_AMOUNT)).status(ApprovalStatus.PENDING).build();
    }
    void pending() {
        when(approvals.findById(9L)).thenReturn(approval()); when(approvals.findByIdForUpdate(9L)).thenReturn(approval());
        when(terminations.lockTermination(7L)).thenReturn(new TerminationApprovalRepository.TerminationApprovalView(
                7L,1L,TerminationStatus.WAITING_APPROVAL,List.of(ApprovalReason.HIGH_AMOUNT)));
        when(tellers.findById(1L)).thenReturn(Teller.builder().id(1L).role(TellerRole.TELLER).branchNo("B1").build());
        when(tellers.findById(2L)).thenReturn(Teller.builder().id(2L).role(TellerRole.MANAGER).approvalLimit(10_000_000L).branchNo("B1").build());
    }
    @Test void selfApprovalRejected() {
        pending();
        assertEquals("SELF_APPROVAL",assertThrows(BusinessException.class,
                ()->service.approve(9L,1L,new ApprovalDecisionRequest(1L,"확인"))).getCode());
        verify(approvals,never()).decide(any(),any(),any(),any());
    }
    @Test void managerLimitStillEnforced() {
        pending();
        when(tellers.findById(2L)).thenReturn(Teller.builder().id(2L).role(TellerRole.MANAGER).approvalLimit(9_999_999L).branchNo("B1").build());
        assertEquals("APPROVAL_LIMIT_EXCEEDED",assertThrows(BusinessException.class,
                ()->service.approve(9L,2L,new ApprovalDecisionRequest(2L,"확인"))).getCode());
        verify(approvals,never()).decide(any(),any(),any(),any());
    }
    @Test void decisionStateStillValidated() {
        pending();
        when(terminations.lockTermination(7L)).thenReturn(new TerminationApprovalRepository.TerminationApprovalView(
                7L,1L,TerminationStatus.APPROVED,List.of(ApprovalReason.HIGH_AMOUNT)));
        assertEquals("INVALID_APPROVAL_STATUS",assertThrows(BusinessException.class,
                ()->service.approve(9L,2L,new ApprovalDecisionRequest(2L,"확인"))).getCode());
    }
    @Test void commentAndReasonPersistAsAuditNoteAndStateChanges() {
        pending();
        doReturn(List.<String[]>of(new String[]{"고객","123"})).when(jdbc).query(anyString(),any(RowMapper.class),eq(7L));
        when(approvals.decide(eq(9L),any(),eq(2L),anyString())).thenReturn(1);
        when(terminations.updateStatus(eq(7L),eq(TerminationStatus.WAITING_APPROVAL),any())).thenReturn(1);
        var response=service.approve(9L,2L,new ApprovalDecisionRequest(2L,"확인"));
        assertEquals(9L,response.approvalId()); assertEquals("고객",response.customerName());
        assertEquals("https://channel.example/branch/approvals/9",response._links().get("self").href());
        service.reject(9L,2L,new ApprovalRejectRequest(2L,"위임장 미비"));
        verify(approvals).decide(9L,ApprovalStatus.APPROVED,2L,"확인");
        verify(approvals).decide(9L,ApprovalStatus.REJECTED,2L,"위임장 미비");
        verify(terminations).updateStatus(7L,TerminationStatus.WAITING_APPROVAL,TerminationStatus.APPROVED);
        verify(terminations).updateStatus(7L,TerminationStatus.WAITING_APPROVAL,TerminationStatus.REJECTED);
    }
    @Test void createStillRequiresOriginalTeller() {
        when(terminations.lockTermination(7L)).thenReturn(new TerminationApprovalRepository.TerminationApprovalView(
                7L,1L,TerminationStatus.RECEIVED,List.of(ApprovalReason.HIGH_AMOUNT)));
        assertEquals("NOT_REQUEST_OWNER",assertThrows(BusinessException.class,
                ()->service.create(7L,2L,new ApprovalCreateRequest(2L,"요청"))).getCode());
        verify(approvals,never()).insert(any(),anyList(),any(),any(),any());
    }
}
