package deposit_early_termination_channel.service;


import deposit_early_termination_channel.domain.enums.ApprovalStatus;
import deposit_early_termination_channel.domain.enums.TellerRole;
import deposit_early_termination_channel.domain.enums.TerminationStatus;
import deposit_early_termination_channel.domain.model.Approval;
import deposit_early_termination_channel.domain.model.Teller;
import deposit_early_termination_channel.dto.request.ApprovalCreateRequest;
import deposit_early_termination_channel.dto.request.ApprovalDecisionRequest;
import deposit_early_termination_channel.dto.request.ApprovalRejectRequest;
import deposit_early_termination_channel.dto.response.ApprovalResponse;
import deposit_early_termination_channel.exception.BusinessException;
import deposit_early_termination_channel.repository.ApprovalRepository;
import deposit_early_termination_channel.repository.ApprovalTellerRepository;
import deposit_early_termination_channel.repository.TerminationApprovalRepository;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import deposit_early_termination_channel.dto.response.LinkResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ApprovalService {
    private final ApprovalRepository approvals;
    private final ApprovalTellerRepository tellers;
    private final TerminationApprovalRepository terminations;
    private final JdbcTemplate jdbc;
    private final String publicBaseUrl;

    public ApprovalService(ApprovalRepository approvals, ApprovalTellerRepository tellers,
                           TerminationApprovalRepository terminations, JdbcTemplate jdbc,
                           @Value("${app.public-base-url}") String publicBaseUrl) {
        this.approvals = approvals;
        this.tellers = tellers;
        this.terminations = terminations;
        this.jdbc = jdbc;
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
    }

    @Transactional
    public ApprovalResponse create(Long terminationId, Long actorId, ApprovalCreateRequest request) {
        // 잠금 순서: termination -> approval. A와도 같은 순서로 합의해야 교착 위험이 낮다.
        TerminationApprovalRepository.TerminationApprovalView termination = terminations.lockTermination(terminationId);
        tellers.findById(actorId);
        if (!termination.tellerId().equals(actorId))
            conflict("NOT_REQUEST_OWNER", "해지를 접수한 직원만 승인 요청할 수 있습니다.");
        if (termination.status() != TerminationStatus.RECEIVED)
            conflict("INVALID_TERMINATION_STATUS", "RECEIVED 상태에서만 승인 요청할 수 있습니다.");
        if (termination.reasons().isEmpty())
            conflict("APPROVAL_NOT_REQUIRED", "승인 사유가 없는 접수입니다.");
        Long payout = terminations.findQuotePayout(terminationId);
        Long id = approvals.insert(terminationId, termination.reasons(), payout, actorId, request.memo());
        requireOne(terminations.updateStatus(terminationId, TerminationStatus.RECEIVED,
                TerminationStatus.WAITING_APPROVAL));
        return toResponse(approvals.findById(id));
    }

    @Transactional(readOnly = true)
    public List<ApprovalResponse> list(ApprovalStatus status) {
        return approvals.findByStatus(status).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public ApprovalResponse detail(Long approvalId) {
        return toResponse(approvals.findById(approvalId));
    }

    @Transactional
    public ApprovalResponse approve(Long id, Long actorId, ApprovalDecisionRequest request) {
        return decide(id, actorId, ApprovalStatus.APPROVED, request.comment());
    }

    @Transactional
    public ApprovalResponse reject(Long id, Long actorId, ApprovalRejectRequest request) {
        return decide(id, actorId, ApprovalStatus.REJECTED, request.reason());
    }

    private ApprovalResponse decide(Long id, Long actorId, ApprovalStatus decision, String note) {
        // approval 행을 먼저 읽어 해지 ID를 확인한 다음, 해지 행 -> 승인 행 순으로 잠근다.
        Approval beforeLock = approvals.findById(id);
        TerminationApprovalRepository.TerminationApprovalView termination = terminations.lockTermination(beforeLock.getTerminationId());
        Approval approval = approvals.findByIdForUpdate(id);
        if (approval.getStatus() != ApprovalStatus.PENDING ||
                termination.status() != TerminationStatus.WAITING_APPROVAL)
            conflict("INVALID_APPROVAL_STATUS", "대기 중인 승인 요청만 처리할 수 있습니다.");

        Teller requester = tellers.findById(approval.getRequestedBy());
        Teller approver = tellers.findById(actorId);
        if (actorId.equals(approval.getRequestedBy()) || actorId.equals(termination.tellerId()))
            conflict("SELF_APPROVAL", "본인 접수에 대해 승인 또는 반려할 수 없습니다.");
        if (!requester.getBranchNo().equals(approver.getBranchNo()))
            conflict("BRANCH_MISMATCH", "다른 지점의 승인 요청은 처리할 수 없습니다.");
        if (approver.getRole() == TellerRole.TELLER)
            conflict("NOT_AUTHORIZED", "일반 직원에게 승인 권한이 없습니다.");
        if (approver.getRole() == TellerRole.MANAGER &&
                (approver.getApprovalLimit() == null ||
                        approval.getPayoutAmount() > approver.getApprovalLimit()))
            conflict("APPROVAL_LIMIT_EXCEEDED", "책임자 승인 한도를 초과했습니다.");
        // BRANCH_MANAGER는 금액 한도 없음. 반려에도 동일한 권한 검사를 적용한다.
        requireOne(approvals.decide(id, decision, actorId, note));
        requireOne(terminations.updateStatus(termination.id(),
                TerminationStatus.WAITING_APPROVAL,
                decision == ApprovalStatus.APPROVED ? TerminationStatus.APPROVED : TerminationStatus.REJECTED));
        return toResponse(approvals.findById(id));
    }

    private ApprovalResponse toResponse(Approval a) {
        // The existing five-table schema supplies the related customer/deposit details.
        var owner = jdbc.query("""
            SELECT c.name AS customer_name, bt.deposit_account_no
              FROM branch_termination bt JOIN customer c ON c.id = bt.customer_id
             WHERE bt.id = ?
            """, (rs, row) -> new String[]{rs.getString("customer_name"),
                rs.getString("deposit_account_no")}, a.getTerminationId());
        if (owner.isEmpty()) {
            throw new BusinessException(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", "승인 대상 거래 정보를 찾을 수 없습니다.");
        }
        String base = "/branch/approvals/" + a.getId();
        Map<String, LinkResponse> links = new LinkedHashMap<>();
        links.put("self", new LinkResponse(publicBaseUrl + base));
        links.put("termination", new LinkResponse(publicBaseUrl + "/branch/terminations/" + a.getTerminationId()));
        if (a.getStatus() == ApprovalStatus.PENDING) {
            links.put("approve", new LinkResponse(publicBaseUrl + base + "/approve"));
            links.put("reject", new LinkResponse(publicBaseUrl + base + "/reject"));
        }
        return new ApprovalResponse(a.getId(), a.getTerminationId(), a.getStatus(), a.getReasons(),
            owner.get(0)[0], owner.get(0)[1], a.getPayoutAmount(), a.getRequestedBy(),
            a.getRequestedAt(), a.getMemo(), a.getApproverId(), a.getDecidedAt(),
            a.getDecisionNote(), links);
    }

    private static void requireOne(int rows) {
        if (rows != 1) conflict("CONCURRENT_MODIFICATION", "상태가 변경되어 처리를 완료하지 못했습니다.");
    }

    private static void conflict(String code, String message) {
        throw new BusinessException(HttpStatus.CONFLICT, code, message);
    }
}
