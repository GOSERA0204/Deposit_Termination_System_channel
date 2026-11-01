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
import java.util.Map;

@RestController
@RequestMapping("/branch")
public class ApprovalController {
    private final ApprovalService approvalService;

    public ApprovalController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @PostMapping("/terminations/{id}/approvals")
    public ResponseEntity<ApprovalResponse> create(
            @PathVariable Long id,
            @Valid @RequestBody ApprovalCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(approvalService.create(id, request.requestedBy(), request));
    }

    @GetMapping("/approvals")
    public Map<String, List<ApprovalResponse>> list(
            @RequestParam(defaultValue = "PENDING") ApprovalStatus status) {
        return Map.of("items", approvalService.list(status));
    }

    @GetMapping("/approvals/{approvalId}")
    public ApprovalResponse detail(@PathVariable Long approvalId) {
        return approvalService.detail(approvalId);
    }

    @PostMapping("/approvals/{approvalId}/approve")
    public ApprovalResponse approve(
            @PathVariable Long approvalId,
            @Valid @RequestBody ApprovalDecisionRequest request) {
        return approvalService.approve(approvalId, request.approverId(), request);
    }

    @PostMapping("/approvals/{approvalId}/reject")
    public ApprovalResponse reject(
            @PathVariable Long approvalId,
            @Valid @RequestBody ApprovalRejectRequest request) {
        return approvalService.reject(approvalId, request.approverId(), request);
    }
}

