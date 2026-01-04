package com.doubledump.hash;

import java.nio.file.Path;

public final class HasherException extends Exception {
    private final String reason;
    private final Path problematic;
    private final String detail;

    public HasherException(String reason, Path problematic, String detail) {
        super(reason + (detail == null ? "" : (": " + detail)));
        this.reason = reason;
        this.problematic = problematic;
        this.detail = detail;
    }

    public String reason() {
        return reason;
    }

    public Path problematic() {
        return problematic;
    }

    public String detail() {
        return detail;
    }
}
