package deposit_early_termination_channel.dto.request;

import deposit_early_termination_channel.domain.enums.PayoutMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record TerminationCreateRequest(
        @NotNull @Positive
        Long tellerId,

        @NotBlank @Size(max = 10)
        String terminalNo,

        @NotBlank @Size(max = 20)
        String depositAccountNo,

        @NotNull @Valid
        CustomerInfo customer,

        @NotNull
        Boolean passbookPresented,

        @NotNull @Valid
        Payout payout
) {

    public record CustomerInfo(
            @NotBlank @Size(max = 50)
            String name,

            @NotNull
            Boolean idVerified,

            @NotNull
            Boolean isAgent
    ) {
    }

    public record Payout(
            @NotNull
            PayoutMethod method,

            @Size(max = 20)
            String accountNo
    ) {
    }
}