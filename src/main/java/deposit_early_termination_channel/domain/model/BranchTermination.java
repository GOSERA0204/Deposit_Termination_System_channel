package deposit_early_termination_channel.domain.model;

import com.team.channel.domain.enums.ApprovalReason;
import com.team.channel.domain.enums.PayoutMethod;
import com.team.channel.domain.enums.TerminationStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BranchTermination {

    private Long id;

    // 접수 직원·단말
    private Long tellerId;

    private String terminalNo;

    // 예금 명의자·해지 대상
    private Long customerId;

    private String depositAccountNo;

    // 접수 당시 확인 정보
    private boolean idVerified;

    private boolean isAgent;

    private boolean passbookPresented;

    private boolean pledged;

    // 지급 정보
    private PayoutMethod payoutMethod;

    private String payoutAccountNo;

    // DB 저장·조회 시 쉼표 구분 문자열과 변환
    @Builder.Default
    private List<ApprovalReason> approvalReasons = List.of();

    @Builder.Default
    private TerminationStatus status = TerminationStatus.RECEIVED;

    // 거래 추적·실패 정보
    private String globalTxId;

    private String failReason;

    // 실제 해지 결과: 성공 확정 전에는 null
    private Long actualPrincipal;

    private Long actualInterest;

    private Long actualTax;

    private Long actualPayout;

    private LocalDateTime completedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
