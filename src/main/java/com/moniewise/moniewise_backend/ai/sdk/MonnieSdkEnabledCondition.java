package com.moniewise.moniewise_backend.ai.sdk;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Starts the in-process agent only when it is enabled and a model key is present,
 * so a local Boot process without LLM credentials still loads.
 */
public class MonnieSdkEnabledCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment env = context.getEnvironment();
        if (!env.getProperty("ai.sdk.enabled", Boolean.class, true)) {
            return false;
        }
        return hasText(env, "gemini.api-key")
                || hasText(env, "gemini.apiKey")
                || hasText(env, "GEMINI_API_KEY")
                || hasText(env, "ai.sdk.groq-api-key")
                || hasText(env, "GROQ_API_KEY")
                || hasText(env, "ai.sdk.openai-api-key")
                || hasText(env, "OPENAI_API_KEY")
                || hasEnv("GEMINI_API_KEY")
                || hasEnv("GROQ_API_KEY")
                || hasEnv("OPENAI_API_KEY");
    }

    private static boolean hasEnv(String key) {
        String value = System.getenv(key);
        return value != null && !value.isBlank();
    }

    private static boolean hasText(Environment env, String key) {
        String value = env.getProperty(key);
        return value != null && !value.isBlank();
    }
}
