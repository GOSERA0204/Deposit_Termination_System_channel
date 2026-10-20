package deposit_early_termination_channel.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Customer {

    // 계정계에서 조회한 고객번호
    private Long id;

    private String name;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
