package com.vouchq.scanner;

/** Finding severity. Weights feed the aggregate risk score. */
public enum Severity {
    INFO(10),
    WARN(40),
    CRITICAL(90);

    private final int weight;

    Severity(int weight) {
        this.weight = weight;
    }

    public int weight() {
        return weight;
    }
}
