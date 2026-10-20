package deposit_early_termination_channel.client;

import deposit_early_termination_channel.domain.enums.PayoutMethod;
import deposit_early_termination_channel.domain.model.BranchTermination;
import deposit_early_termination_channel.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class MciClient {

    private final RestClient restClient;

    public MciClient(
            @Value("${mci.base-url}") String baseUrl
    ) {
        var factory = new SimpleClientHttpRequestFactory();

        factory.setConnectTimeout(2000);
        factory.setReadTimeout(5000);

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    public MciResponse inquireDeposit(String accountNo) {
        return send(
                "AQ001",
                null,
                null,
                null,
                Map.of("depositAccountNo", accountNo)
        );
    }

    public MciResponse inquireQuote(
            String accountNo,
            Long tellerId,
            String terminalNo
    ) {
        return send(
                "DQ001",
                null,
                "T-" + tellerId,
                terminalNo,
                Map.of("depositAccountNo", accountNo)
        );
    }

    public MciResponse execute(
            BranchTermination termination,
            Long approvalId
    ) {
        Map<String, Object> payout = new LinkedHashMap<>();

        payout.put(
                "method",
                termination.getPayoutMethod().name()
        );

        if (termination.getPayoutMethod()
                == PayoutMethod.TRANSFER) {
            payout.put(
                    "accountNo",
                    termination.getPayoutAccountNo()
            );
        }

        Map<String, Object> body = new LinkedHashMap<>();

        body.put(
                "depositAccountNo",
                termination.getDepositAccountNo()
        );
        body.put("payout", payout);
        body.put("isAgent", termination.isAgent());
        body.put("approvalId", approvalId);

        return send(
                "DT001",
                "B-" + termination.getId(),
                "T-" + termination.getTellerId(),
                termination.getTerminalNo(),
                body
        );
    }

    private MciResponse send(
            String txCode,
            String channelTxId,
            String operatorId,
            String terminalNo,
            Map<String, Object> body
    ) {
        Map<String, Object> header = new LinkedHashMap<>();

        header.put("channelCode", "CH01");
        header.put("txCode", txCode);

        if (channelTxId != null) {
            header.put("channelTxId", channelTxId);
        }

        if (operatorId != null) {
            header.put("operatorId", operatorId);
        }

        if (terminalNo != null) {
            header.put("terminalNo", terminalNo);
        }

        try {
            MciResponse response = restClient.post()
                    .uri("/mci/transactions")
                    .body(
                            Map.of(
                                    "header", header,
                                    "body", body
                            )
                    )
                    .retrieve()
                    .body(MciResponse.class);

            if (response == null || response.header() == null) {
                throw new BusinessException(
                        502,
                        "UPSTREAM_ERROR",
                        "MCI 응답 형식이 올바르지 않습니다."
                );
            }

            return response;

        } catch (RestClientResponseException e) {
            boolean timeout =
                    e.getStatusCode().value() == 504;

            throw new BusinessException(
                    timeout ? 504 : 502,
                    timeout
                            ? "UPSTREAM_TIMEOUT"
                            : "UPSTREAM_ERROR",
                    "MCI 호출에 실패했습니다."
            );

        } catch (ResourceAccessException e) {
            throw new BusinessException(
                    504,
                    "UPSTREAM_TIMEOUT",
                    "MCI 연결 또는 응답을 확인하지 못했습니다."
            );

        } catch (RestClientException e) {
            throw new BusinessException(
                    502,
                    "UPSTREAM_ERROR",
                    "MCI 응답 처리에 실패했습니다."
            );
        }
    }

    public record MciHeader(
            String globalTxId,
            String channelCode,
            String channelTxId,
            String txCode,
            String result,
            String errorCode
    ) {
    }

    public record MciResponse(
            MciHeader header,
            Map<String, Object> body
    ) {

        public boolean success() {
            return "SUCCESS".equals(header.result());
        }

        public String text(String key) {
            return required(key).toString();
        }

        public Long number(String key) {
            return new BigDecimal(
                    required(key).toString()
            ).longValueExact();
        }

        public BigDecimal decimal(String key) {
            return new BigDecimal(
                    required(key).toString()
            );
        }

        public LocalDate date(String key) {
            return LocalDate.parse(text(key));
        }

        public boolean bool(String key) {
            Object value = required(key);

            if (value instanceof Boolean booleanValue) {
                return booleanValue;
            }

            throw new BusinessException(
                    502,
                    "UPSTREAM_ERROR",
                    "MCI 필드 타입이 올바르지 않습니다: " + key
            );
        }

        private Object required(String key) {
            Object value = body == null
                    ? null
                    : body.get(key);

            if (value == null) {
                throw new BusinessException(
                        502,
                        "UPSTREAM_ERROR",
                        "MCI 응답 필드가 없습니다: " + key
                );
            }

            return value;
        }
    }
}