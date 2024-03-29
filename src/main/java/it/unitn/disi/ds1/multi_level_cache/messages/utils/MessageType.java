package it.unitn.disi.ds1.multi_level_cache.messages.utils;

public enum MessageType {

    WRITE,
    READ,
    CRASH,
    RECOVER,
    REFILL,
    FILL,
    JOIN,
    CRITICAL_WRITE,
    CRITICAL_WRITE_ABORT,
    CRITICAL_WRITE_COMMIT,
    CRITICAL_WRITE_REQUEST,
    CRITICAL_WRITE_VOTE,
    CRITICAL_READ,
    FLUSH,
    TIMEOUT,
    INIT_WRITE,
    INIT_READ,
    WRITE_CONFIRM,
    READ_REPLY,
    ERROR,
    ;

    @Override
    public String toString() {
        switch (this) {
            case CRASH -> {
                return "CRASH";
            }
            case CRITICAL_READ -> {
                return "CRIT-READ";
            }
            case CRITICAL_WRITE -> {
                return "CRIT-WRITE";
            }
            case CRITICAL_WRITE_ABORT -> {
                return "CRIT-WRITE-ABORT";
            }
            case CRITICAL_WRITE_COMMIT -> {
                return "CRIT-WRITE-COMMIT";
            }
            case CRITICAL_WRITE_REQUEST -> {
                return "CRIT-WRITE-REQUEST";
            }
            case CRITICAL_WRITE_VOTE -> {
                return "CRIT-WRITE-VOTE";
            }
            case ERROR -> {
                return "ERROR";
            }
            case FILL -> {
                return "FILL";
            }
            case FLUSH -> {
                return "FLUSH";
            }
            case INIT_READ -> {
                return "INIT-READ";
            }
            case INIT_WRITE -> {
                return "INIT-WRITE";
            }
            case JOIN -> {
                return "JOIN";
            }
            case READ -> {
                return "READ";
            }
            case READ_REPLY -> {
                return "READ-REPLY";
            }
            case RECOVER -> {
                return "RECOVER";
            }
            case REFILL -> {
                return "REFILL";
            }
            case TIMEOUT -> {
                return "TIMEOUT";
            }
            case WRITE -> {
                return "WRITE";
            }
            case WRITE_CONFIRM -> {
                return "WRITE-CONFIRM";
            }
            default -> {
                return "";
            }
        }
    }
}
