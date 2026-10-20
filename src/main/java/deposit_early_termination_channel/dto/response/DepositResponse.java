package deposit_early_termination_channel.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

public record DepositResponse(
        String depositAccountNo,
        Long customerId,
        String customerName,
        String productCode,
        String productName,
        Long principal,
        LocalDate openedAt,
        LocalDate maturityAt,
        BigDecimal baseRate,
        boolean pledged,
        String status,
        Map<String, LinkResponse> _links
) {
}