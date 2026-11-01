package deposit_early_termination_channel;

import deposit_early_termination_channel.controller.*;
import deposit_early_termination_channel.dto.request.*;
import deposit_early_termination_channel.dto.response.*;
import deposit_early_termination_channel.domain.enums.*;
import deposit_early_termination_channel.exception.*;
import deposit_early_termination_channel.service.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ChannelApplicationTests {
    TerminationService terminations;
    ApprovalService approvals;
    MockMvc mvc;
    static final String CREATE = """
        {"tellerId":1,"terminalNo":"T1","depositAccountNo":"123",
         "customer":{"name":"고객","idVerified":true,"isAgent":false},
         "passbookPresented":true,"payout":{"method":"CASH"}}
        """;

    @BeforeEach void setup() {
        terminations = mock(TerminationService.class);
        approvals = mock(ApprovalService.class);
        mvc = MockMvcBuilders.standaloneSetup(new TerminationController(terminations),
                new ApprovalController(approvals)).setControllerAdvice(new ApprovalExceptionHandler()).build();
    }

    static TerminationResponse response(TerminationStatus status) {
        return new TerminationResponse(7L, "B-7", status, 1L, "T1", "123", "고객", false, true,
                new PayoutResponse(PayoutMethod.CASH, null),
                new TerminationQuoteResponse(100L, 10, new BigDecimal("2.5"), BigDecimal.ONE, 2L, 0L, 102L),
                List.of(), null, null, null, LocalDateTime.of(2026,11,1,12,0),
                LocalDateTime.of(2026,11,1,12,0),
                Map.of("self", new LinkResponse("http://localhost:8081/branch/terminations/7")));
    }
    static ApprovalResponse approval() {
        return new ApprovalResponse(9L, 7L, ApprovalStatus.PENDING, List.of(ApprovalReason.HIGH_AMOUNT),
                "고객", "123", 10_000_000L, 1L, LocalDateTime.of(2026,11,1,12,0), "요청",
                null, null, null, Map.of("self",new LinkResponse("http://localhost:8081/branch/approvals/9")));
    }
    @Test void sixTerminationRoutesAndResponseContract() throws Exception {
        when(terminations.getDeposit("123")).thenReturn(new DepositResponse("123",1L,"고객","P1","예금",100L,
                LocalDate.of(2026,1,1),LocalDate.of(2027,1,1),BigDecimal.ONE,false,"ACTIVE",Map.of()));
        when(terminations.create(any())).thenReturn(response(TerminationStatus.RECEIVED));
        when(terminations.getAll(null)).thenReturn(List.of(response(TerminationStatus.RECEIVED)));
        when(terminations.get(7L)).thenReturn(response(TerminationStatus.RECEIVED));
        when(terminations.execute(7L,1L)).thenReturn(response(TerminationStatus.COMPLETED));
        when(terminations.getReceipt(7L)).thenReturn(new TerminationReceiptResponse(7L,"G1","123","고객",100L,10,
                BigDecimal.ONE,2L,0L,102L,new PayoutResponse(PayoutMethod.CASH,null),1L,2L,
                LocalDateTime.of(2026,11,1,12,0),Map.of()));
        mvc.perform(get("/branch/deposits/123")).andExpect(status().isOk()).andExpect(jsonPath("$.depositAccountNo").value("123"));
        mvc.perform(post("/branch/terminations").contentType(MediaType.APPLICATION_JSON).content(CREATE))
                .andExpect(status().isCreated()).andExpect(header().string("Location","http://localhost:8081/branch/terminations/7"))
                .andExpect(jsonPath("$.channelTxId").value("B-7"))
                .andExpect(jsonPath("$.isAgent").value(false)).andExpect(jsonPath("$.passbookPresented").value(true))
                .andExpect(jsonPath("$.payout.method").value("CASH")).andExpect(jsonPath("$.quote.baseRate").value(2.5))
                .andExpect(jsonPath("$.updatedAt").isString()).andExpect(jsonPath("$.payoutMethod").doesNotExist());
        verify(terminations).create(new TerminationCreateRequest(1L,"T1","123",
                new TerminationCreateRequest.CustomerInfo("고객",true,false),true,
                new TerminationCreateRequest.Payout(PayoutMethod.CASH,null)));
        mvc.perform(get("/branch/terminations")).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(7));
        mvc.perform(get("/branch/terminations/7")).andExpect(status().isOk()).andExpect(jsonPath("$._links.self.href").isString());
        mvc.perform(post("/branch/terminations/7/execute").contentType(MediaType.APPLICATION_JSON).content("{\"tellerId\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        verify(terminations).execute(7L,1L);
        mvc.perform(get("/branch/terminations/7/receipt")).andExpect(status().isOk())
                .andExpect(jsonPath("$.elapsedDays").value(10)).andExpect(jsonPath("$.approverId").value(2));
    }
    @Test void fiveApprovalRoutesUseJsonActors() throws Exception {
        when(approvals.create(eq(7L),eq(1L),any())).thenReturn(approval());
        when(approvals.list(ApprovalStatus.PENDING)).thenReturn(List.of(approval()));
        when(approvals.detail(9L)).thenReturn(approval());
        when(approvals.approve(eq(9L),eq(2L),any())).thenReturn(approval());
        when(approvals.reject(eq(9L),eq(2L),any())).thenReturn(approval());
        mvc.perform(post("/branch/terminations/7/approvals").contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestedBy\":1,\"memo\":\"요청\"}")).andExpect(status().isCreated());
        verify(approvals).create(7L,1L,new ApprovalCreateRequest(1L,"요청"));
        mvc.perform(get("/branch/approvals")).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].approvalId").value(9));
        mvc.perform(get("/branch/approvals/9")).andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalId").value(9)).andExpect(jsonPath("$.customerName").value("고객"))
                .andExpect(jsonPath("$.depositAccountNo").value("123")).andExpect(jsonPath("$.id").doesNotExist());
        mvc.perform(post("/branch/approvals/9/approve").contentType(MediaType.APPLICATION_JSON)
                .content("{\"approverId\":2,\"comment\":\"확인\"}")).andExpect(status().isOk());
        verify(approvals).approve(9L,2L,new ApprovalDecisionRequest(2L,"확인"));
        mvc.perform(post("/branch/approvals/9/reject").contentType(MediaType.APPLICATION_JSON)
                .content("{\"approverId\":2,\"reason\":\"미비\"}")).andExpect(status().isOk());
        verify(approvals).reject(9L,2L,new ApprovalRejectRequest(2L,"미비"));
    }
    @Test void invalidRequestsReturnCommonError() throws Exception {
        mvc.perform(post("/branch/approvals/9/reject").contentType(MediaType.APPLICATION_JSON)
                .content("{\"approverId\":2,\"reason\":\" \"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.path").value("/branch/approvals/9/reject")).andExpect(jsonPath("$.timestamp").isString());
        mvc.perform(post("/branch/terminations/7/execute").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/branch/terminations?status=INVALID")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.path").value("/branch/terminations"));
        verifyNoInteractions(terminations,approvals);
    }
    @Test void unknownAndProcessingKeepAcceptedContract() throws Exception {
        for (var state : List.of(TerminationStatus.UNKNOWN,TerminationStatus.PROCESSING)) {
            when(terminations.execute(7L,1L)).thenReturn(response(state));
            mvc.perform(post("/branch/terminations/7/execute").contentType(MediaType.APPLICATION_JSON).content("{\"tellerId\":1}"))
                    .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value(state.name()));
        }
    }
    @Test void businessErrorIncludesPathAndTimestamp() throws Exception {
        when(terminations.get(7L)).thenThrow(new BusinessException(org.springframework.http.HttpStatus.NOT_FOUND,"TRANSACTION_NOT_FOUND","없음"));
        mvc.perform(get("/branch/terminations/7")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/branch/terminations/7")).andExpect(jsonPath("$.timestamp").isString());
    }
}
