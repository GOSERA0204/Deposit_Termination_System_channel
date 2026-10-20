package deposit_early_termination_channel.dto.request;

import jakarta.validation.constraints.Size;

// 승인 사유와 금액은 요청값을 신뢰하지 않고 DB의 접수·견적에서 가져온다.
public record ApprovalCreateRequest(@Size(max = 200) String memo) {}
