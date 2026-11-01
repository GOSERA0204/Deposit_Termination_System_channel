package deposit_early_termination_channel.dto.response;
import deposit_early_termination_channel.domain.enums.PayoutMethod;
public record PayoutResponse(PayoutMethod method, String accountNo) {}
