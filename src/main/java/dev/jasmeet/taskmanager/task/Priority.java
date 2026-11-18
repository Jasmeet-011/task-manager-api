package dev.jasmeet.taskmanager.task;

/**
 * The rank is written out for each constant, never taken from ordinal(). Task stores it in the
 * priority_rank column so priority can be sorted by meaning (LOW < MEDIUM < HIGH) and not
 * alphabetically. Reordering or inserting constants here cannot change what is stored.
 */
public enum Priority {
    LOW(1),
    MEDIUM(2),
    HIGH(3);

    private final int rank;

    Priority(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }
}
