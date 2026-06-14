/**
 * <b>ingestion</b> — the first collection path (MA3-75): connect a Git source,
 * fetch it with JGit, parse Skills via the OSS {@code :parser}, and persist them
 * as inventory (RegisteredServer / Tool / ToolVersion). Re-ingest is idempotent
 * on the parser's stable {@code definitionHash}; a changed hash inserts a new
 * version, the foundation drift detection (MA3-78) builds on.
 */
package com.vouchq.ingestion;
