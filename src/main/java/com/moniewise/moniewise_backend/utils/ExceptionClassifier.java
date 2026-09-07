package com.moniewise.moniewise_backend.utils;

import java.sql.SQLTransientConnectionException;
import java.util.Locale;

public final class ExceptionClassifier {

    private ExceptionClassifier() {
    }

    public static boolean isDatabasePoolExhausted(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLTransientConnectionException) {
                return true;
            }

            String className = current.getClass().getName();
            String message = current.getMessage();
            if (containsIgnoreCase(className, "SQLTransientConnectionException")
                    || (containsIgnoreCase(message, "HikariPool")
                    && containsIgnoreCase(message, "Connection is not available"))
                    || (containsIgnoreCase(message, "Connection is not available")
                    && containsIgnoreCase(message, "request timed out"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    public static String rootCauseMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root != null && root.getCause() != null) {
            root = root.getCause();
        }
        if (root == null) {
            return "unknown";
        }
        String message = root.getMessage();
        return message == null || message.isBlank()
                ? root.getClass().getSimpleName()
                : message;
    }

    private static boolean containsIgnoreCase(String value, String needle) {
        return value != null
                && needle != null
                && value.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }
}
