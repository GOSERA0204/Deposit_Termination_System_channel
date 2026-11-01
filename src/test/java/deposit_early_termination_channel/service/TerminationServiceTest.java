package deposit_early_termination_channel.service;

import deposit_early_termination_channel.client.MciClient;
import deposit_early_termination_channel.domain.enums.*;
import deposit_early_termination_channel.domain.model.*;
import deposit_early_termination_channel.dto.request.TerminationCreateRequest;
import deposit_early_termination_channel.exception.BusinessException;
import deposit_early_termination_channel.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TerminationServiceTest {
    TerminationRepository repository;
    TerminationQuoteRepository quotes;
    CustomerRepository customers;
    MciClient mci;
    JdbcTemplate jdbc;
    TerminationService service;
    @BeforeEach void setup() {
        repository=mock(TerminationRepository.class); quotes=mock(TerminationQuoteRepository.class);
        customers=mock(CustomerRepository.class); mci=mock(MciClient.class); jdbc=mock(JdbcTemplate.class);
        service=new TerminationService(repository,quotes,customers,mci,jdbc,
                new TransactionTemplate(mock(PlatformTransactionManager.class)),"https://channel.example/");
    }
    BranchTermination transaction(TerminationStatus state) {
        return BranchTermination.builder().id(7L).tellerId(1L).customerId(3L).depositAccountNo("123")
                .payoutMethod(PayoutMethod.CASH).passbookPresented(true).status(state).build();
    }
    void responseData() {
        when(quotes.findByTerminationId(7L)).thenReturn(TerminationQuote.builder().principal(100L).elapsedDays(10)
                .baseRate(new BigDecimal("2.5")).appliedRate(BigDecimal.ONE).interest(2L).tax(0L).payoutAmount(102L).build());
        when(customers.findName(3L)).thenReturn("고객");
    }
    MciClient.MciResponse result(String state,String code) {
        return new MciClient.MciResponse(new MciClient.MciHeader("G1","CH01","B-7","DT001",state,code),
                Map.of("principal",100,"interest",2,"tax",0,"payoutAmount",102));
    }
    @Test void wrongExecutionOwnerRejectedBeforeMci() {
        when(repository.findById(7L)).thenReturn(Optional.of(transaction(TerminationStatus.RECEIVED)));
        assertEquals("NOT_REQUEST_OWNER",assertThrows(BusinessException.class,()->service.execute(7L,2L)).getCode());
        verifyNoInteractions(mci); verify(repository,never()).startProcessing(any());
    }
    @ParameterizedTest @CsvSource({"UNKNOWN","PROCESSING","COMPLETED"})
    void existingResultNeverResends(TerminationStatus state) {
        when(repository.findById(7L)).thenReturn(Optional.of(transaction(state))); responseData();
        assertEquals(state,service.execute(7L,1L).status()); verifyNoInteractions(mci);
    }
    @ParameterizedTest @CsvSource({"UPSTREAM_NOT_SENT,FAILED","UPSTREAM_TIMEOUT,UNKNOWN","UPSTREAM_ERROR,UNKNOWN"})
    void networkClassificationPreserved(String code,TerminationStatus state) {
        var current=transaction(TerminationStatus.RECEIVED);
        when(repository.findById(7L)).thenReturn(Optional.of(current),Optional.of(transaction(state)));
        when(repository.startProcessing(7L)).thenReturn(1);
        when(mci.execute(current,null)).thenThrow(new BusinessException(HttpStatus.BAD_GATEWAY,code,"오류"));
        when(repository.finishWithError(7L,state,null,code)).thenReturn(1); responseData();
        assertEquals(state,service.execute(7L,1L).status());
        verify(repository).finishWithError(7L,state,null,code);
    }
    @ParameterizedTest @CsvSource({"NETWORK","FAIL"})
    void failedUpdateIsNotReportedAsSaved(String mode) {
        var current=transaction(TerminationStatus.RECEIVED);
        when(repository.findById(7L)).thenReturn(Optional.of(current)); when(repository.startProcessing(7L)).thenReturn(1);
        if(mode.equals("NETWORK")) when(mci.execute(current,null)).thenThrow(new BusinessException(HttpStatus.GATEWAY_TIMEOUT,"UPSTREAM_TIMEOUT","오류"));
        else when(mci.execute(current,null)).thenReturn(result("FAIL","NOT_BUSINESS_DAY"));
        assertEquals("INVALID_STATE_TRANSITION",assertThrows(BusinessException.class,()->service.execute(7L,1L)).getCode());
    }
    @Test void confirmedFailSavedThenBusinessErrorThrown() {
        var current=transaction(TerminationStatus.RECEIVED);
        when(repository.findById(7L)).thenReturn(Optional.of(current)); when(repository.startProcessing(7L)).thenReturn(1);
        when(mci.execute(current,null)).thenReturn(result("FAIL","NOT_BUSINESS_DAY"));
        when(repository.finishWithError(7L,TerminationStatus.FAILED,"G1","NOT_BUSINESS_DAY")).thenReturn(1);
        assertEquals("NOT_BUSINESS_DAY",assertThrows(BusinessException.class,()->service.execute(7L,1L)).getCode());
    }
    @Test void successCompletesAndMapsResponse() {
        var current=transaction(TerminationStatus.RECEIVED);
        when(repository.findById(7L)).thenReturn(Optional.of(current),Optional.of(transaction(TerminationStatus.COMPLETED)));
        when(repository.startProcessing(7L)).thenReturn(1); when(mci.execute(current,null)).thenReturn(result("SUCCESS",null));
        when(repository.complete(7L,"G1",100L,2L,0L,102L)).thenReturn(1); responseData();
        var response=service.execute(7L,1L);
        assertEquals(TerminationStatus.COMPLETED,response.status()); assertEquals("B-7",response.channelTxId());
        assertEquals(new BigDecimal("2.5"),response.quote().baseRate());
        assertEquals("https://channel.example/branch/terminations/7/receipt",response._links().get("receipt").href());
        assertFalse(response._links().containsKey("inquire")); assertFalse(response._links().containsKey("cancel"));
    }
    @Test void approvedExecutionStillPassesApprovalIdToMci() {
        var current=transaction(TerminationStatus.APPROVED);
        when(repository.findById(7L)).thenReturn(Optional.of(current),Optional.of(transaction(TerminationStatus.COMPLETED)));
        doReturn(List.of(9L)).when(jdbc).query(contains("AND status = 'APPROVED'"),any(RowMapper.class),eq(7L));
        when(repository.startProcessing(7L)).thenReturn(1);
        when(mci.execute(current,9L)).thenReturn(result("SUCCESS",null));
        when(repository.complete(7L,"G1",100L,2L,0L,102L)).thenReturn(1); responseData();
        assertEquals(TerminationStatus.COMPLETED,service.execute(7L,1L).status());
        verify(mci).execute(current,9L);
    }
    @Test void successUpdateMustAffectExactlyOneRow() {
        var current=transaction(TerminationStatus.RECEIVED);
        when(repository.findById(7L)).thenReturn(Optional.of(current));
        when(repository.startProcessing(7L)).thenReturn(1);
        when(mci.execute(current,null)).thenReturn(result("SUCCESS",null));
        assertEquals("INVALID_STATE_TRANSITION",assertThrows(BusinessException.class,()->service.execute(7L,1L)).getCode());
        verify(repository,never()).finishWithError(any(),any(),any(),any());
    }
    @Test void approvalRequiredReceptionOffersRequestRatherThanExecute() {
        var current=BranchTermination.builder().id(7L).tellerId(1L).customerId(3L).depositAccountNo("123")
                .payoutMethod(PayoutMethod.CASH).approvalReasons(List.of(ApprovalReason.HIGH_AMOUNT)).build();
        when(repository.findById(7L)).thenReturn(Optional.of(current)); responseData();
        var links=service.get(7L)._links();
        assertTrue(links.containsKey("request-approval")); assertFalse(links.containsKey("execute"));
        assertFalse(links.containsKey("cancel")); assertFalse(links.containsKey("inquire"));
    }
    @Test void receiptUsesApproverEmployeeIdRatherThanApprovalId() {
        when(repository.findById(7L)).thenReturn(Optional.of(transaction(TerminationStatus.COMPLETED))); responseData();
        doReturn(List.of(2L)).when(jdbc).query(contains("SELECT approver_id"),any(RowMapper.class),eq(7L));
        var receipt=service.getReceipt(7L);
        assertEquals(2L,receipt.approverId()); assertEquals(10,receipt.elapsedDays());
        assertEquals("https://channel.example/branch/terminations/7",receipt._links().get("termination").href());
    }
    @ParameterizedTest @CsvSource({"10000000,false,true,false,HIGH_AMOUNT","100,false,true,false,NONE",
            "100,true,true,false,AGENT","100,false,false,false,NO_PASSBOOK","100,false,true,true,PLEDGED"})
    void creationPreservesApprovalPolicies(long payout,boolean agent,boolean passbook,boolean pledged,String reason) {
        when(jdbc.queryForObject(anyString(),eq(Integer.class),eq(1L))).thenReturn(1);
        when(mci.inquireDeposit("123")).thenReturn(new MciClient.MciResponse(
                new MciClient.MciHeader("G1","CH01",null,"AQ001","SUCCESS",null),
                Map.ofEntries(Map.entry("depositAccountNo","123"),Map.entry("customerId",3),Map.entry("customerName","고객"),
                        Map.entry("productCode","P1"),Map.entry("productName","예금"),Map.entry("principal",100),
                        Map.entry("openedAt","2026-01-01"),Map.entry("maturityAt","2027-01-01"),
                        Map.entry("baseRate","2.5"),Map.entry("pledged",pledged),Map.entry("status","ACTIVE"))));
        when(mci.inquireQuote("123",1L,"T1")).thenReturn(new MciClient.MciResponse(
                new MciClient.MciHeader("G2","CH01",null,"DQ001","SUCCESS",null),
                Map.of("principal",100,"elapsedDays",10,"appliedRate",1,"interest",2,"tax",0,"payoutAmount",payout)));
        when(repository.save(any())).thenReturn(7L);
        when(repository.findById(7L)).thenReturn(Optional.of(transaction(TerminationStatus.RECEIVED))); responseData();
        service.create(new TerminationCreateRequest(1L,"T1","123",new TerminationCreateRequest.CustomerInfo("고객",true,agent),
                passbook,new TerminationCreateRequest.Payout(PayoutMethod.CASH,null)));
        var capture=org.mockito.ArgumentCaptor.forClass(BranchTermination.class); verify(repository).save(capture.capture());
        assertEquals(reason.equals("NONE")?List.of():List.of(ApprovalReason.valueOf(reason)),capture.getValue().getApprovalReasons());
        var quote=org.mockito.ArgumentCaptor.forClass(TerminationQuote.class); verify(quotes).save(quote.capture());
        assertEquals(new BigDecimal("2.5"),quote.getValue().getBaseRate());
    }
}
