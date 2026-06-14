/**
 * <b>audit</b> — append-only, tamper-evident event log. Each entry chains via
 * {@code entry_hash = SHA-256(prev_hash + entry)} so any mid-stream edit breaks
 * the chain. Source of compliance evidence. See Linear MA3-79.
 */
package com.vouchq.audit;
