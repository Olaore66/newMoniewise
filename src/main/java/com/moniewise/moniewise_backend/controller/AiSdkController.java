package com.moniewise.moniewise_backend.controller;

import com.moniewise.monnie.api.action.PreparedAction;
import com.moniewise.moniewise_backend.ai.sdk.AiSdkService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-process Monnie agent. Existing Gemini routes on {@link AiController} stay.
 */
@RestController
@RequestMapping("/ai/sdk")
public class AiSdkController {

    private final AiSdkService sdk;

    public AiSdkController(AiSdkService sdk) {
        this.sdk = sdk;
    }

    @PostMapping("/turn")
    public ResponseEntity<?> turn(@RequestBody TurnBody body, Authentication authentication) {
        if (body == null || body.text == null || body.text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "text is required"));
        }
        AiSdkService.TurnPayload result = sdk.turn(
                authentication.getName(), body.threadId, body.text, body.surface);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("threadId", result.threadId());
        payload.put("turnId", result.turnId());
        payload.put("text", result.text());
        payload.put("finishReason", result.finishReason());
        payload.put("actionsProposed", result.actionsProposed());
        payload.put("frames", result.frames());
        return ResponseEntity.ok(payload);
    }

    @PostMapping("/actions/{id}/confirm")
    public ResponseEntity<?> confirm(@PathVariable("id") String id,
                                     @RequestBody(required = false) ConfirmBody body,
                                     Authentication authentication,
                                     HttpServletRequest request) {
        ConfirmBody payload = body == null ? new ConfirmBody() : body;
        PreparedAction action = sdk.confirm(
                authentication.getName(), id, payload.pin, payload.paramsHash,
                payload.edits, request.getRemoteAddr());
        return ResponseEntity.ok(toMap(action));
    }

    @PostMapping("/actions/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable("id") String id, Authentication authentication) {
        return ResponseEntity.ok(toMap(sdk.cancel(authentication.getName(), id)));
    }

    private static Map<String, Object> toMap(PreparedAction action) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", action.id());
        payload.put("status", action.status().name());
        payload.put("kind", action.kind().name());
        payload.put("requiresPin", action.requiresPin());
        payload.put("failureCode", action.failureCode());
        payload.put("executionRef", action.executionRef());
        return payload;
    }

    public static class TurnBody {
        public String threadId;
        public String text;
        public String surface;
    }

    public static class ConfirmBody {
        public String pin;
        public String paramsHash;
        public Map<String, Object> edits;
    }
}
