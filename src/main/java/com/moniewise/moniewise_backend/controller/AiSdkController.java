package com.moniewise.moniewise_backend.controller;

import com.moniewise.monnie.api.action.PreparedAction;
import com.moniewise.moniewise_backend.ai.sdk.AiSdkService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-process Monnie chat API. Existing Gemini routes on {@link AiController} stay.
 */
@RestController
@RequestMapping("/ai/sdk")
public class AiSdkController {

    private final AiSdkService sdk;

    public AiSdkController(AiSdkService sdk) {
        this.sdk = sdk;
    }

    @PostMapping("/threads")
    public ResponseEntity<?> createThread(@RequestBody(required = false) ThreadBody body,
                                          Authentication authentication) {
        ThreadBody payload = body == null ? new ThreadBody() : body;
        return ResponseEntity.ok(sdk.createThread(
                authentication.getName(), payload.surface, payload.title, payload.instructions));
    }

    @GetMapping("/threads")
    public ResponseEntity<?> listThreads(
            @RequestParam(required = false) String surface,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant before,
            @RequestParam(required = false) Integer limit,
            Authentication authentication,
            HttpServletRequest request) {
        return ResponseEntity.ok(sdk.listThreads(
                authentication.getName(), surface, before, limit, request.getRemoteAddr()));
    }

    @GetMapping("/threads/{id}")
    public ResponseEntity<?> getThread(@PathVariable("id") String id, Authentication authentication) {
        return ResponseEntity.ok(sdk.getThread(authentication.getName(), id));
    }

    @PatchMapping("/threads/{id}")
    public ResponseEntity<?> patchThread(@PathVariable("id") String id,
                                         @RequestBody(required = false) ThreadBody body,
                                         Authentication authentication) {
        ThreadBody payload = body == null ? new ThreadBody() : body;
        return ResponseEntity.ok(sdk.patchThread(
                authentication.getName(), id, payload.title, payload.instructions));
    }

    @DeleteMapping("/threads/{id}")
    public ResponseEntity<?> deleteThread(@PathVariable("id") String id,
                                          Authentication authentication) {
        sdk.deleteThread(authentication.getName(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/threads/{id}/messages")
    public ResponseEntity<?> messages(@PathVariable("id") String id,
                                      @RequestParam(required = false) Long after,
                                      @RequestParam(required = false) Integer limit,
                                      Authentication authentication,
                                      HttpServletRequest request) {
        return ResponseEntity.ok(sdk.messages(
                authentication.getName(), id, after, limit, request.getRemoteAddr()));
    }

    @GetMapping("/threads/{id}/actions")
    public ResponseEntity<?> actions(@PathVariable("id") String id, Authentication authentication) {
        return ResponseEntity.ok(sdk.pendingActions(authentication.getName(), id));
    }

    @PostMapping("/turn")
    public ResponseEntity<?> turn(@RequestBody TurnBody body, Authentication authentication,
                                  HttpServletRequest request) {
        if (body == null || body.text == null || body.text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "text is required"));
        }
        AiSdkService.TurnPayload result = sdk.turn(
                authentication.getName(), body.threadId, body.text, body.surface,
                body.instructions, request.getRemoteAddr());
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
        public String instructions;
    }

    public static class ThreadBody {
        public String surface;
        public String title;
        public String instructions;
    }

    public static class ConfirmBody {
        public String pin;
        public String paramsHash;
        public Map<String, Object> edits;
    }
}
