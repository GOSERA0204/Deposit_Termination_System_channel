package deposit_early_termination_channel.domain.model;

import deposit_early_termination_channel.domain.enums.TellerRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Teller {

    private Long id;

    private String name;

    private TellerRole role;

    // 직급과 함께 해석
    // TELLER: 승인 불가
    // MANAGER: 승인 한도
    // BRANCH_MANAGER: null이면 무제한
    private Long approvalLimit;

    private String branchNo;
}
