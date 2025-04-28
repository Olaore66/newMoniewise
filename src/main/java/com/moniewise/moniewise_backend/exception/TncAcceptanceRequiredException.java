package com.moniewise.moniewise_backend.exception;

import lombok.*;

@Getter
@Setter
@Data
public class TncAcceptanceRequiredException extends RuntimeException {
    private final String tncContent;
    private final String tncVersion;

    public TncAcceptanceRequiredException(String message, String tncContent, String tncVersion) {
        super(message);
        this.tncContent = tncContent;
        this.tncVersion = tncVersion;
    }

    // Getters
    public String getTncContent() { return tncContent; }
    public String getTncVersion() { return tncVersion; }
}