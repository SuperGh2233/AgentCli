package com.paicli.snapshot;

import java.io.IOException;
import java.nio.file.Path;

/** Test-only fault hook. Rollback has its own phase; a forward fault must not be replayed. */
@FunctionalInterface
interface RestoreFileOps {
    RestoreFileOps NONE = (rollback, operation, path) -> { };

    void beforeMutation(boolean rollback, String operation, Path path) throws IOException;
}
