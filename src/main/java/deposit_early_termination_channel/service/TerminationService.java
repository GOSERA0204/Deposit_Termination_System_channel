package deposit_early_termination_channel.service;

import deposit_early_termination_channel.client.MciClient;
import deposit_early_termination_channel.client.MciClient.MciResponse;
import deposit_early_termination_channel.domain.enums.ApprovalReason;
import deposit_early_termination_channel.domain.enums.PayoutMethod;
import deposit_early_termination_channel.domain.enums.TerminationStatus;
import deposit_early_termination_channel.domain.model.BranchTermination;
import deposit_early_termination_channel.domain.model.TerminationQuote;
import deposit_early_termination_channel.dto.request.TerminationCreateRequest;
import deposit_early_termination_channel.dto.response.DepositResponse;
import deposit_early_termination_channel.dto.response.LinkResponse;
import deposit_early_termination_channel.dto.response.TerminationQuoteResponse;
import deposit_early_termination_channel.dto.response.TerminationReceiptResponse;
import deposit_early_termination_channel.dto.response.TerminationResponse;
import deposit_early_termination_channel.exception.BusinessException;
import deposit_early_termination_channel.repository.CustomerRepository;
import deposit_early_termination_channel.repository.TerminationQuoteRepository;
import deposit_early_termination_channel.repository.TerminationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class TerminationService {

    private final TerminationRepository terminationRepository;
    private final TerminationQuoteRepository quoteRepository;
    private final CustomerRepository customerRepository;

    private final MciClient mciClient;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    private final String publicBaseUrl;

    public TerminationService(
            TerminationRepository terminationRepository,
            TerminationQuoteRepository quoteRepository,
            CustomerRepository customerRepository,
            MciClient mciClient,
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            @Value("${app.public-base-url}") String publicBaseUrl
    ) {
        this.terminationRepository = terminationRepository;
        this.quoteRepository = quoteRepository;
        this.customerRepository = customerRepository;
        this.mciClient = mciClient;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
    }

    public DepositResponse getDeposit(String accountNo) {
        MciResponse response =
                mciClient.inquireDeposit(accountNo);

        requireSuccess(response);

        return new DepositResponse(
                response.text("depositAccountNo"),
                response.number("customerId"),
                response.text("customerName"),
                response.text("productCode"),
                response.text("productName"),
                response.number("principal"),
                response.date("openedAt"),
                response.date("maturityAt"),
                response.decimal("baseRate"),
                response.bool("pledged"),
                response.text("status"),
                Map.of(
                        "self",
                        link("/branch/deposits/" + accountNo)
                )
        );
    }

    public TerminationResponse create(
            TerminationCreateRequest request
    ) {
        validateRequest(request);

        DepositResponse deposit =
                getDeposit(request.depositAccountNo());

        if (!"ACTIVE".equals(deposit.status())) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "DEPOSIT_ALREADY_TERMINATED",
                    "해지 가능한 상태의 예금이 아닙니다."
            );
        }

        MciResponse quote = mciClient.inquireQuote(
                request.depositAccountNo(),
                request.tellerId(),
                request.terminalNo()
        );

        requireSuccess(quote);

        List<ApprovalReason> reasons = new ArrayList<>();

        if (quote.number("payoutAmount") >= 10_000_000L) {
            reasons.add(ApprovalReason.HIGH_AMOUNT);
        }

        if (request.customer().isAgent()) {
            reasons.add(ApprovalReason.AGENT);
        }

        if (!request.passbookPresented()) {
            reasons.add(ApprovalReason.NO_PASSBOOK);
        }

        if (deposit.pledged()) {
            reasons.add(ApprovalReason.PLEDGED);
        }

        Long id = transactionTemplate.execute(txStatus -> {
            // 고객번호·이름은 계정계에서 조회한 값을 사용
            customerRepository.upsert(
                    deposit.customerId(),
                    deposit.customerName()
            );

            Long terminationId = terminationRepository.save(
                    BranchTermination.builder()
                            .tellerId(request.tellerId())
                            .terminalNo(request.terminalNo())
                            .customerId(deposit.customerId())
                            .depositAccountNo(
                                    request.depositAccountNo()
                            )
                            .idVerified(true)
                            .isAgent(
                                    request.customer().isAgent()
                            )
                            .passbookPresented(
                                    request.passbookPresented()
                            )
                            .pledged(deposit.pledged())
                            .payoutMethod(
                                    request.payout().method()
                            )
                            .payoutAccountNo(
                                    request.payout().method()
                                            == PayoutMethod.CASH
                                            ? null
                                            : request.payout().accountNo()
                            )
                            .approvalReasons(
                                    List.copyOf(reasons)
                            )
                            .status(TerminationStatus.RECEIVED)
                            .build()
            );

            quoteRepository.save(
                    TerminationQuote.builder()
                            .terminationId(terminationId)
                            .principal(
                                    quote.number("principal")
                            )
                            .elapsedDays(
                                    Math.toIntExact(
                                            quote.number("elapsedDays")
                                    )
                            )
                            .appliedRate(
                                    quote.decimal("appliedRate")
                            )
                            .interest(
                                    quote.number("interest")
                            )
                            .tax(
                                    quote.number("tax")
                            )
                            .payoutAmount(
                                    quote.number("payoutAmount")
                            )
                            // 현재 예시에서는 견적 수신 시각
                            .quotedAt(LocalDateTime.now())
                            .build()
            );

            return terminationId;
        });

        return get(Objects.requireNonNull(id));
    }

    public List<TerminationResponse> getAll(
            TerminationStatus status
    ) {
        return terminationRepository.findAll(status)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public TerminationResponse get(Long id) {
        return toResponse(findRequired(id));
    }

    public TerminationResponse execute(Long id) {
        BranchTermination current = findRequired(id);

        if (hasExistingResult(current.getStatus())) {
            return toResponse(current);
        }

        Long approvalId = findApprovedId(current);

        // 1. PROCESSING 변경 후 커밋
        Boolean started = transactionTemplate.execute(
                txStatus ->
                        terminationRepository.startProcessing(id) == 1
        );

        if (!Boolean.TRUE.equals(started)) {
            BranchTermination latest = findRequired(id);

            if (hasExistingResult(latest.getStatus())) {
                return toResponse(latest);
            }

            throw invalidState();
        }

        // 2. DB 트랜잭션 밖에서 MCI 호출
        MciResponse result;

        try {
            result = mciClient.execute(
                    current,
                    approvalId
            );

            if (result.success()) {
                validateExecutionResult(result);

            } else if (!"FAIL".equals(
                    result.header().result()
            )) {
                throw new BusinessException(
                        HttpStatus.BAD_GATEWAY,
                        "UPSTREAM_ERROR",
                        "해지 결과가 확정되지 않았습니다."
                );
            }

        } catch (RuntimeException e) {
            // 요청 이후 결과가 불명확하면 실패로 단정하지 않음
            String errorCode =
                    e instanceof BusinessException businessException
                            ? businessException.getCode()
                            : "UPSTREAM_ERROR";

            transactionTemplate.executeWithoutResult(
                    txStatus ->
                            terminationRepository.finishWithError(
                                    id,
                                    TerminationStatus.UNKNOWN,
                                    null,
                                    errorCode
                            )
            );

            return get(id);
        }

        // 3. 확정된 업무 실패 저장
        if (!result.success()) {
            String errorCode =
                    result.header().errorCode() == null
                            ? "UPSTREAM_ERROR"
                            : result.header().errorCode();

            transactionTemplate.executeWithoutResult(
                    txStatus ->
                            terminationRepository.finishWithError(
                                    id,
                                    TerminationStatus.FAILED,
                                    result.header().globalTxId(),
                                    errorCode
                            )
            );

            throw new BusinessException(
                    httpStatus(errorCode),
                    errorCode,
                    "해지를 처리할 수 없습니다: " + errorCode
            );
        }

        // 4. 실제 실행 결과 저장
        transactionTemplate.executeWithoutResult(txStatus -> {
            int changed = terminationRepository.complete(
                    id,
                    result.header().globalTxId(),
                    result.number("principal"),
                    result.number("interest"),
                    result.number("tax"),
                    result.number("payoutAmount")
            );

            if (changed != 1) {
                throw invalidState();
            }
        });

        return get(id);
    }

    public TerminationReceiptResponse getReceipt(Long id) {
        BranchTermination termination = findRequired(id);

        if (termination.getStatus()
                != TerminationStatus.COMPLETED) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "INVALID_STATE_TRANSITION",
                    "완료된 해지 거래만 계산서를 조회할 수 있습니다."
            );
        }

        return new TerminationReceiptResponse(
                termination.getId(),
                termination.getGlobalTxId(),
                customerRepository.findName(
                        termination.getCustomerId()
                ),
                termination.getDepositAccountNo(),
                termination.getActualPrincipal(),
                termination.getActualInterest(),
                termination.getActualTax(),
                termination.getActualPayout(),
                termination.getPayoutMethod(),
                termination.getPayoutAccountNo(),
                termination.getCompletedAt()
        );
    }

    private void validateRequest(
            TerminationCreateRequest request
    ) {
        if (!request.customer().idVerified()) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "ID_NOT_VERIFIED",
                    "실명확인이 필요합니다."
            );
        }

        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM teller
                WHERE id = ?
                """,
                Integer.class,
                request.tellerId()
        );

        if (count == null || count == 0) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST",
                    "존재하지 않는 직원입니다."
            );
        }

        if (request.payout().method()
                == PayoutMethod.TRANSFER
                && (
                request.payout().accountNo() == null
                        || request.payout().accountNo().isBlank()
        )) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST",
                    "입금 계좌번호가 필요합니다."
            );
        }
    }

    private Long findApprovedId(
            BranchTermination termination
    ) {
        if (termination.getStatus()
                == TerminationStatus.RECEIVED
                && termination.getApprovalReasons().isEmpty()) {
            return null;
        }

        if (termination.getStatus()
                != TerminationStatus.APPROVED) {
            throw invalidState();
        }

        List<Long> approvalIds = jdbcTemplate.query("""
                SELECT id
                FROM approval
                WHERE termination_id = ?
                  AND status = 'APPROVED'
                """,
                (rs, rowNum) -> rs.getLong("id"),
                termination.getId()
        );

        if (approvalIds.isEmpty()) {
            throw invalidState();
        }

        return approvalIds.get(0);
    }

    private boolean hasExistingResult(
            TerminationStatus status
    ) {
        return status == TerminationStatus.COMPLETED
                || status == TerminationStatus.PROCESSING
                || status == TerminationStatus.UNKNOWN;
    }

    private void validateExecutionResult(
            MciResponse result
    ) {
        String globalTxId = result.header().globalTxId();

        if (globalTxId == null || globalTxId.isBlank()) {
            throw new BusinessException(
                    HttpStatus.BAD_GATEWAY,
                    "UPSTREAM_ERROR",
                    "MCI 거래고유번호가 없습니다."
            );
        }

        result.number("principal");
        result.number("interest");
        result.number("tax");
        result.number("payoutAmount");
    }

    private BranchTermination findRequired(Long id) {
        return terminationRepository.findById(id)
                .orElseThrow(() ->
                        new BusinessException(
                                HttpStatus.NOT_FOUND,
                                "TRANSACTION_NOT_FOUND",
                                "해지 접수 건을 찾을 수 없습니다."
                        )
                );
    }

    private TerminationResponse toResponse(
            BranchTermination termination
    ) {
        TerminationQuote quote =
                quoteRepository.findByTerminationId(
                        termination.getId()
                );

        return new TerminationResponse(
                termination.getId(),
                termination.getStatus(),

                termination.getTellerId(),
                termination.getTerminalNo(),

                termination.getCustomerId(),
                customerRepository.findName(
                        termination.getCustomerId()
                ),
                termination.getDepositAccountNo(),

                termination.getPayoutMethod(),
                termination.getPayoutAccountNo(),

                new TerminationQuoteResponse(
                        quote.getPrincipal(),
                        quote.getElapsedDays(),
                        quote.getAppliedRate(),
                        quote.getInterest(),
                        quote.getTax(),
                        quote.getPayoutAmount()
                ),

                termination.getApprovalReasons(),
                termination.getGlobalTxId(),

                termination.getActualPrincipal(),
                termination.getActualInterest(),
                termination.getActualTax(),
                termination.getActualPayout(),

                termination.getFailReason(),
                termination.getCreatedAt(),
                termination.getCompletedAt(),

                createLinks(termination)
        );
    }

    private Map<String, LinkResponse> createLinks(
            BranchTermination termination
    ) {
        String path =
                "/branch/terminations/" + termination.getId();

        Map<String, LinkResponse> links =
                new LinkedHashMap<>();

        links.put("self", link(path));

        switch (termination.getStatus()) {
            case RECEIVED -> {
                if (termination.getApprovalReasons().isEmpty()) {
                    links.put(
                            "execute",
                            link(path + "/execute")
                    );
                } else {
                    links.put(
                            "request-approval",
                            link(path + "/approvals")
                    );
                }

                links.put(
                        "deposit",
                        link(
                                "/branch/deposits/"
                                        + termination.getDepositAccountNo()
                        )
                );
            }

            case APPROVED -> links.put(
                    "execute",
                    link(path + "/execute")
            );

            case COMPLETED -> {
                links.put(
                        "receipt",
                        link(path + "/receipt")
                );

                links.put(
                        "deposit",
                        link(
                                "/branch/deposits/"
                                        + termination.getDepositAccountNo()
                        )
                );
            }

            default -> {
                // 취소·UNKNOWN 재조회 API는 현재 범위에서 제외
            }
        }

        List<Long> approvalIds = jdbcTemplate.query("""
                SELECT id
                FROM approval
                WHERE termination_id = ?
                """,
                (rs, rowNum) -> rs.getLong("id"),
                termination.getId()
        );

        if (!approvalIds.isEmpty()) {
            links.put(
                    "approval",
                    link(
                            "/branch/approvals/"
                                    + approvalIds.get(0)
                    )
            );
        }

        return links;
    }

    private LinkResponse link(String path) {
        return new LinkResponse(publicBaseUrl + path);
    }

    private void requireSuccess(MciResponse response) {
        if (!response.success()) {
            String errorCode =
                    response.header().errorCode() == null
                            ? "UPSTREAM_ERROR"
                            : response.header().errorCode();

            throw new BusinessException(
                    httpStatus(errorCode),
                    errorCode,
                    "MCI 업무 처리에 실패했습니다: " + errorCode
            );
        }
    }

    private BusinessException invalidState() {
        return new BusinessException(
                HttpStatus.CONFLICT,
                "INVALID_STATE_TRANSITION",
                "현재 상태에서는 해지를 실행할 수 없습니다."
        );
    }

    private HttpStatus httpStatus(String errorCode) {
        return switch (errorCode) {
            case "INVALID_REQUEST",
                 "ID_NOT_VERIFIED" -> HttpStatus.BAD_REQUEST;

            case "CHANNEL_NOT_ALLOWED" -> HttpStatus.FORBIDDEN;

            case "DEPOSIT_NOT_FOUND",
                 "ACCOUNT_NOT_FOUND",
                 "TRANSACTION_NOT_FOUND" -> HttpStatus.NOT_FOUND;

            case "DEPOSIT_ALREADY_TERMINATED",
                 "INVALID_STATE_TRANSITION" -> HttpStatus.CONFLICT;

            case "NOT_BUSINESS_DAY",
                 "BRANCH_ONLY",
                 "MOBILE_LIMIT_EXCEEDED" -> HttpStatus.UNPROCESSABLE_CONTENT;

            case "ROUTE_DISABLED" -> HttpStatus.SERVICE_UNAVAILABLE;

            case "UPSTREAM_TIMEOUT" -> HttpStatus.GATEWAY_TIMEOUT;

            default -> HttpStatus.BAD_GATEWAY;
        };
    }
}