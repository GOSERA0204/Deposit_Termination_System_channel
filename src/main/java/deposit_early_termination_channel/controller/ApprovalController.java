package deposit_early_termination_channel.controller;


import deposit_early_termination_channel.domain.enums.ApprovalStatus;
import deposit_early_termination_channel.dto.request.ApprovalCreateRequest;
import deposit_early_termination_channel.dto.request.ApprovalDecisionRequest;
import deposit_early_termination_channel.dto.request.ApprovalRejectRequest;
import deposit_early_termination_channel.dto.response.ApprovalResponse;
import deposit_early_termination_channel.service.ApprovalService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/branch")
public class ApprovalController {
    private final ApprovalService approvalService;

    public ApprovalController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    // X-Teller-Id는 로컬 실습용. 운영환경에서는 인증 컨텍스트에서 직원 ID를 가져와야 한다.
    @PostMapping("/terminations/{id}/approvals")
    public ResponseEntity<ApprovalResponse> create(
            @PathVariable Long id,
            @RequestHeader("X-Teller-Id") Long actorId,
            @Valid @RequestBody ApprovalCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(approvalService.create(id, actorId, request));
    }

    @GetMapping("/approvals")
    public List<ApprovalResponse> list(
            @RequestParam(defaultValue = "PENDING") ApprovalStatus status) {
        return approvalService.list(status);
    }

    @GetMapping("/approvals/{approvalId}")
    public ApprovalResponse detail(@PathVariable Long approvalId) {
        return approvalService.detail(approvalId);
    }

    @PostMapping("/approvals/{approvalId}/approve")
    public ApprovalResponse approve(
            @PathVariable Long approvalId,
            @RequestHeader("X-Teller-Id") Long actorId,
            @Valid @RequestBody ApprovalDecisionRequest request) {
        return approvalService.approve(approvalId, actorId, request);
    }

    @PostMapping("/approvals/{approvalId}/reject")
    public ApprovalResponse reject(
            @PathVariable Long approvalId,
            @RequestHeader("X-Teller-Id") Long actorId,
            @Valid @RequestBody ApprovalRejectRequest request) {
        return approvalService.reject(approvalId, actorId, request);
    }
}

