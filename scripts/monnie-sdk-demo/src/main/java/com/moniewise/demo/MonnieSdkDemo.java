package com.moniewise.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.moniewise.monnie.api.chat.MonnieChatModel;
import com.moniewise.monnie.api.event.AiEventFrame;
import com.moniewise.monnie.api.event.TurnEventSink;
import com.moniewise.monnie.api.model.UserRef;
import com.moniewise.monnie.core.Monnie;
import com.moniewise.monnie.core.MonnieConfig;
import com.moniewise.monnie.core.turn.TurnRequest;
import com.moniewise.monnie.core.turn.TurnResult;
import com.moniewise.monnie.gemini.GeminiChatModel;
import com.moniewise.monnie.gemini.GeminiConfig;
import com.moniewise.monnie.openai.OpenAiChatModel;
import com.moniewise.monnie.openai.OpenAiCompatibleConfig;
import com.moniewise.monnie.testkit.FakeHostPorts;
import com.moniewise.monnie.testkit.FakePlanReadPort;
import com.moniewise.monnie.testkit.InMemoryConversationStore;
import com.moniewise.monnie.testkit.InMemoryPreparedActionStore;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * BrandDrive-style consumer: resolve monnieSDK from GitHub Packages (or a local
 * {@code mvn install}) and run an in-process agent. Does not execute money unless
 * {@code --confirm} is pointed at a running backend with a session token and PIN.
 */
public final class MonnieSdkDemo {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public static void main(String[] args) throws Exception {
        List<String> flags = List.of(args);
        if (flags.contains("--confirm")) {
            confirmLive(flags);
            return;
        }
        if (flags.contains("--live-api")) {
            liveTurn();
            return;
        }
        fixtureTurns(flags);
    }

    private static void fixtureTurns(List<String> flags) throws Exception {
        MonnieChatModel model = modelFromEnv();
        FakePlanReadPort plan = FakePlanReadPort.withSampleBudget();
        InMemoryPreparedActionStore actions = new InMemoryPreparedActionStore();
        try (Monnie monnie = Monnie.create(MonnieConfig.builder(model, plan, actions)
                .conversations(new InMemoryConversationStore())
                .ports(FakeHostPorts.sample())
                .build())) {
            UserRef user = UserRef.of("demo@moniewise.local");
            String thread = runPrinted(monnie, user, null,
                    "How much can I spend on food today?", null);
            String instructions = flagValue(flags, "--instructions");
            if (instructions == null) {
                instructions = "You are helping a new user. After they name an envelope, "
                        + "ask for cadence and limit, then prepare it.";
            }
            runPrinted(monnie, user, thread,
                    "Prepare a new envelope called Treats with 5000 naira in my active budget.",
                    instructions);
        }
    }

    private static String runPrinted(Monnie monnie, UserRef user, String threadId, String text,
                                     String instructions)
            throws Exception {
        TurnEventSink.Collecting sink = new TurnEventSink.Collecting();
        TurnRequest request = TurnRequest.of(user, threadId, text);
        if (instructions != null && !instructions.isBlank()) {
            request = request.withInstructions(instructions);
        }
        TurnResult result = monnie.run(request, sink);
        System.out.println("=== turn " + result.turnId() + " reason=" + result.finishReason() + " ===");
        System.out.println(result.text());
        System.out.println("--- frames ---");
        for (AiEventFrame frame : sink.frames()) {
            System.out.println(frame.type() + " seq=" + frame.seq());
        }
        System.out.println(JSON.writeValueAsString(Map.of(
                "threadId", result.threadId(),
                "finishReason", result.finishReason(),
                "actionsProposed", result.actionsProposed(),
                "frameTypes", sink.types())));
        return result.threadId();
    }

    private static void liveTurn() throws Exception {
        String base = required("MONNIE_API_BASE_URL");
        String token = required("MONNIE_API_TOKEN");
        String body = JSON.writeValueAsString(Map.of(
                "text", "How much can I spend on food today?",
                "surface", "CHAT"));
        HttpRequest request = HttpRequest.newBuilder(URI.create(trimSlash(base) + "/ai/sdk/turn"))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        System.out.println(response.statusCode());
        System.out.println(response.body());
        if (response.statusCode() >= 400) {
            System.exit(1);
        }
    }

    private static void confirmLive(List<String> flags) throws Exception {
        String base = required("MONNIE_API_BASE_URL");
        String token = required("MONNIE_API_TOKEN");
        String pin = System.getenv("MONNIE_TX_PIN");
        if (pin == null || pin.isBlank()) {
            throw new IllegalStateException("--confirm needs MONNIE_TX_PIN and a running backend.");
        }
        String actionId = flagValue(flags, "--action-id");
        if (actionId == null) {
            throw new IllegalStateException("--confirm requires --action-id <id> from a prior turn.");
        }
        String body = JSON.writeValueAsString(Map.of("pin", pin));
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(trimSlash(base) + "/ai/sdk/actions/" + actionId + "/confirm"))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        System.out.println(response.statusCode());
        System.out.println(response.body());
        if (response.statusCode() >= 400) {
            System.exit(1);
        }
    }

    private static MonnieChatModel modelFromEnv() {
        String requested = trim(System.getenv("MONNIE_LLM_PROVIDER"));
        String gemini = trim(System.getenv("GEMINI_API_KEY"));
        String groq = trim(System.getenv("GROQ_API_KEY"));
        String openai = trim(System.getenv("OPENAI_API_KEY"));
        if (requested == null) {
            if (gemini != null) {
                return new GeminiChatModel(GeminiConfig.of(gemini, System.getenv("GEMINI_MODEL")));
            }
            if (groq != null) {
                String model = first(System.getenv("GROQ_MODEL"), OpenAiCompatibleConfig.GROQ_DEFAULT_MODEL);
                return new OpenAiChatModel(OpenAiCompatibleConfig.groq(groq, model));
            }
            if (openai != null) {
                String model = first(System.getenv("OPENAI_MODEL"), OpenAiCompatibleConfig.OPENAI_DEFAULT_MODEL);
                return new OpenAiChatModel(OpenAiCompatibleConfig.openai(openai, model));
            }
            throw new IllegalStateException(
                    "Set GEMINI_API_KEY, GROQ_API_KEY or OPENAI_API_KEY (Java does not read .env).");
        }
        return switch (requested.toLowerCase(Locale.ROOT)) {
            case "gemini" -> new GeminiChatModel(GeminiConfig.of(required("GEMINI_API_KEY"),
                    System.getenv("GEMINI_MODEL")));
            case "groq" -> new OpenAiChatModel(OpenAiCompatibleConfig.groq(required("GROQ_API_KEY"),
                    first(System.getenv("GROQ_MODEL"), OpenAiCompatibleConfig.GROQ_DEFAULT_MODEL)));
            case "openai" -> new OpenAiChatModel(OpenAiCompatibleConfig.openai(required("OPENAI_API_KEY"),
                    first(System.getenv("OPENAI_MODEL"), OpenAiCompatibleConfig.OPENAI_DEFAULT_MODEL)));
            default -> throw new IllegalStateException("Unknown MONNIE_LLM_PROVIDER: " + requested);
        };
    }

    private static String flagValue(List<String> flags, String name) {
        int index = flags.indexOf(name);
        if (index < 0 || index + 1 >= flags.size()) {
            return null;
        }
        return flags.get(index + 1);
    }

    private static String required(String name) {
        String value = trim(System.getenv(name));
        if (value == null) {
            throw new IllegalStateException(name + " is not set.");
        }
        return value;
    }

    private static String first(String a, String b) {
        String trimmed = trim(a);
        return trimmed == null ? b : trimmed;
    }

    private static String trim(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String trimSlash(String base) {
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }
}
