package com.vouchq.scanner;

/** The class of risk a finding represents. */
public enum Category {
    PROMPT_INJECTION,
    SECRET,
    DATA_EXFILTRATION,
    DANGEROUS_COMMAND
}
