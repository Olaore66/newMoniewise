package com.moniewise.moniewise_backend.ai.sdk;

import com.moniewise.monnie.api.action.ActionKind;
import com.moniewise.monnie.api.chat.MonnieChatModel;
import com.moniewise.monnie.api.port.ClockPort;
import com.moniewise.monnie.api.port.ConversationStore;
import com.moniewise.monnie.api.port.PlanReadPort;
import com.moniewise.monnie.api.port.PreparedActionStore;
import com.moniewise.monnie.api.port.RateLimiterPort;
import com.moniewise.monnie.core.Monnie;
import com.moniewise.monnie.core.MonnieConfig;
import com.moniewise.monnie.core.turn.TurnEngine;
import com.moniewise.monnie.gemini.GeminiChatModel;
import com.moniewise.monnie.gemini.GeminiConfig;
import com.moniewise.monnie.openai.OpenAiChatModel;
import com.moniewise.monnie.openai.OpenAiCompatibleConfig;
import com.moniewise.moniewise_backend.config.GeminiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

@Configuration
@EnableConfigurationProperties(MonnieSdkProperties.class)
public class MonnieSdkConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MonnieSdkConfiguration.class);

    static final String PROVIDER_GEMINI = "gemini";
    static final String PROVIDER_GROQ = "groq";
    static final String PROVIDER_OPENAI = "openai";

    @Bean
    public ClockPort monnieClockPort() {
        return ClockPort.systemLagos();
    }

    /**
     * The selected chat model, shared by the agent and the status endpoint.
     *
     * <p>Separate from the {@link Monnie} bean so both consume one instance: each adapter
     * owns an HTTP client, and resolving twice would quietly double them.
     */
    @Bean
    @Conditional(MonnieSdkEnabledCondition.class)
    public ResolvedModel monnieChatModel(MonnieSdkProperties properties, GeminiProperties gemini) {
        return resolveModel(properties, gemini);
    }

    @Bean(destroyMethod = "close")
    @Conditional(MonnieSdkEnabledCondition.class)
    public Monnie monnie(MonnieSdkProperties properties, ResolvedModel resolved,
                         PlanReadPort plan, ConversationStore conversations,
                         PreparedActionStore preparedActions, SdkHostReads reads,
                         RateLimiterPort limiter, ClockPort clock) {
        Set<ActionKind> kinds = parseKinds(properties);
        log.info("Starting in-process Monnie agent provider={} model={} kinds={}",
                resolved.provider(), resolved.modelId(), kinds.size());
        return Monnie.create(MonnieConfig.builder(resolved.model(), plan, preparedActions)
                .conversations(conversations)
                .ports(reads.ports())
                .clock(clock)
                .limiter(limiter)
                .enabledKinds(kinds)
                .turnConfig(new TurnEngine.Config(
                        properties.getHistoryLimit(),
                        Duration.ofSeconds(properties.getTurnBudgetSeconds()),
                        true))
                .build());
    }

    @Bean
    @Conditional(MonnieSdkEnabledCondition.class)
    public SdkRuntimeInfo monnieRuntimeInfo(MonnieSdkProperties properties, ResolvedModel resolved) {
        return new SdkRuntimeInfo(
                resolved.provider(),
                resolved.modelId(),
                properties.getHistoryLimit(),
                properties.getTurnBudgetSeconds(),
                parseKinds(properties));
    }

    private static Set<ActionKind> parseKinds(MonnieSdkProperties properties) {
        if (properties.getEnabledKinds() == null || properties.getEnabledKinds().isEmpty()) {
            return EnumSet.allOf(ActionKind.class);
        }
        EnumSet<ActionKind> kinds = EnumSet.noneOf(ActionKind.class);
        for (String raw : properties.getEnabledKinds()) {
            kinds.add(ActionKind.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        }
        return kinds;
    }

    private static ResolvedModel resolveModel(MonnieSdkProperties properties, GeminiProperties gemini) {
        String requested = trim(properties.getProvider());
        if (requested == null) {
            if (hasText(gemini.getApiKey())) {
                return geminiModel(gemini, properties.getModel());
            }
            if (hasText(properties.getGroqApiKey()) || hasText(System.getenv("GROQ_API_KEY"))) {
                return groqModel(first(properties.getGroqApiKey(), System.getenv("GROQ_API_KEY")),
                        properties.getModel());
            }
            if (hasText(properties.getOpenaiApiKey()) || hasText(System.getenv("OPENAI_API_KEY"))) {
                return openaiModel(first(properties.getOpenaiApiKey(), System.getenv("OPENAI_API_KEY")),
                        properties.getModel());
            }
            throw new IllegalStateException(
                    "ai.sdk.enabled is true but no Gemini, Groq, or OpenAI API key is configured.");
        }
        return switch (requested.toLowerCase(Locale.ROOT)) {
            case PROVIDER_GEMINI -> geminiModel(gemini, properties.getModel());
            case PROVIDER_GROQ -> groqModel(first(properties.getGroqApiKey(), System.getenv("GROQ_API_KEY")),
                    properties.getModel());
            case PROVIDER_OPENAI -> openaiModel(first(properties.getOpenaiApiKey(), System.getenv("OPENAI_API_KEY")),
                    properties.getModel());
            default -> throw new IllegalStateException("Unknown ai.sdk.provider: " + requested);
        };
    }

    private static ResolvedModel geminiModel(GeminiProperties gemini, String override) {
        String key = first(gemini.getApiKey(), System.getenv("GEMINI_API_KEY"));
        if (!hasText(key)) {
            throw new IllegalStateException("Gemini API key is missing for the Monnie SDK.");
        }
        String model = hasText(override) ? override : gemini.getModel();
        MonnieChatModel chat = new GeminiChatModel(GeminiConfig.of(key, model));
        return new ResolvedModel(chat, PROVIDER_GEMINI, model);
    }

    private static ResolvedModel groqModel(String key, String override) {
        if (!hasText(key)) {
            throw new IllegalStateException("GROQ API key is missing for the Monnie SDK.");
        }
        String model = hasText(override) ? override : OpenAiCompatibleConfig.GROQ_DEFAULT_MODEL;
        MonnieChatModel chat = new OpenAiChatModel(OpenAiCompatibleConfig.groq(key, model));
        return new ResolvedModel(chat, PROVIDER_GROQ, model);
    }

    private static ResolvedModel openaiModel(String key, String override) {
        if (!hasText(key)) {
            throw new IllegalStateException("OpenAI API key is missing for the Monnie SDK.");
        }
        String model = hasText(override) ? override : OpenAiCompatibleConfig.OPENAI_DEFAULT_MODEL;
        MonnieChatModel chat = new OpenAiChatModel(OpenAiCompatibleConfig.openai(key, model));
        return new ResolvedModel(chat, PROVIDER_OPENAI, model);
    }

    private static String trim(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String first(String a, String b) {
        return hasText(a) ? a : b;
    }
}
