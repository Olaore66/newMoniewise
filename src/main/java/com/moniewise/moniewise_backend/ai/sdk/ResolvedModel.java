package com.moniewise.moniewise_backend.ai.sdk;

import com.moniewise.monnie.api.chat.MonnieChatModel;

/**
 * The chat model that was selected, plus the two labels describing it.
 *
 * <p>A bean of its own so {@code Monnie} and {@code SdkRuntimeInfo} share one model
 * instance. Resolving twice would open a second HTTP client against the provider, and
 * reporting the provider by reflecting on the adapter's class name would say
 * "OpenAiChatModel" for Groq, which is exactly the sort of thing an operator reads off a
 * status endpoint and then debugs for an hour.
 *
 * @param provider {@code gemini | groq | openai}
 * @param modelId the provider's own model identifier, e.g. {@code openai/gpt-oss-120b}
 */
public record ResolvedModel(MonnieChatModel model, String provider, String modelId) {
}
