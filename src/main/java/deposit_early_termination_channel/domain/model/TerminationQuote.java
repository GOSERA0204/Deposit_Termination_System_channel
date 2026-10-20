package deposit_early_termination_channel.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TerminationQuote {

    private Long id;

    private Long terminationId;

    private Long principal;

    private Integer elapsedDays;

    // 연 % 단위: 1.7500 = 연 1.75%
    private BigDecimal appliedRate;

    private Long interest;

    private Long tax;

    private Long payoutAmount;

    private LocalDateTime quotedAt;
}
