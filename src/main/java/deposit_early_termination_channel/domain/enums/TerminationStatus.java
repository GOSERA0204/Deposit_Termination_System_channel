package deposit_early_termination_channel.domain.enums;

public enum TerminationStatus {
    RECEIVED,
    WAITING_APPROVAL,
    APPROVED,
    REJECTED,
    PROCESSING,
    COMPLETED,
    FAILED,
    UNKNOWN
}