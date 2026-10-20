package deposit_early_termination_channel.controller;

import deposit_early_termination_channel.domain.enums.TerminationStatus;
import deposit_early_termination_channel.dto.request.TerminationCreateRequest;
import deposit_early_termination_channel.dto.response.DepositResponse;
import deposit_early_termination_channel.dto.response.TerminationReceiptResponse;
import deposit_early_termination_channel.dto.response.TerminationResponse;
import deposit_early_termination_channel.service.TerminationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/branch")
@RequiredArgsConstructor
public class TerminationController {

    private final TerminationService terminationService;

    @GetMapping("/deposits/{no}")
    public DepositResponse getDeposit(
            @PathVariable("no") String no
    ) {
        return terminationService.getDeposit(no);
    }

    @PostMapping("/terminations")
    public ResponseEntity<TerminationResponse> create(
            @Valid @RequestBody TerminationCreateRequest request
    ) {
        TerminationResponse response =
                terminationService.create(request);

        URI location = URI.create(
                response._links().get("self").href()
        );

        return ResponseEntity.created(location)
                .body(response);
    }

    @GetMapping("/terminations")
    public Map<String, List<TerminationResponse>> getAll(
            @RequestParam(
                    name = "status",
                    required = false
            ) TerminationStatus status
    ) {
        return Map.of(
                "items",
                terminationService.getAll(status)
        );
    }

    @GetMapping("/terminations/{id}")
    public TerminationResponse get(
            @PathVariable("id") Long id
    ) {
        return terminationService.get(id);
    }

    @PostMapping("/terminations/{id}/execute")
    public ResponseEntity<TerminationResponse> execute(
            @PathVariable("id") Long id
    ) {
        TerminationResponse response =
                terminationService.execute(id);

        if (response.status() == TerminationStatus.PROCESSING
                || response.status() == TerminationStatus.UNKNOWN) {
            return ResponseEntity.accepted()
                    .body(response);
        }

        return ResponseEntity.ok(response);
    }

    @GetMapping("/terminations/{id}/receipt")
    public TerminationReceiptResponse getReceipt(
            @PathVariable("id") Long id
    ) {
        return terminationService.getReceipt(id);
    }
}