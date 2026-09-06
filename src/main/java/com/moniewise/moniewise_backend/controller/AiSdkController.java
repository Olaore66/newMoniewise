package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.ai.sdk.AiSdkService;
import com.moniewise.moniewise_backend.dto.request.AiSdkConfirmActionRequest;
import com.moniewise.moniewise_backend.dto.request.AiSdkCreateThreadRequest;
import com.moniewise.moniewise_backend.dto.request.AiSdkPatchThreadRequest;
import com.moniewise.moniewise_backend.dto.request.AiSdkTurnRequest;
import com.moniewise.moniewise_backend.dto.response.ai.AiSdkActionResponse;
import com.moniewise.moniewise_backend.dto.response.ai.AiSdkActionResultResponse;
import com.moniewise.moniewise_backend.dto.response.ai.AiSdkCapabilitiesResponse;
import com.moniewise.moniewise_backend.dto.response.ai.AiSdkMessageResponse;
import com.moniewise.moniewise_backend.dto.response.ai.AiSdkStatusResponse;
import com.moniewise.moniewise_backend.dto.response.ai.AiSdkThreadResponse;
import com.moniewise.moniewise_backend.dto.response.ai.AiSdkTurnAcceptedResponse;
import com.moniewise.moniewise_backend.dto.response.ai.AiSdkTurnResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
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
import javax.validation.Valid;
import java.time.Instant;
import java.util.List;

/**
 * The complete in-process Monnie chat API.
 *
 * <p>Everything a client needs is here, so no caller constructs or touches the SDK
 * directly. Start at {@code GET /ai/sdk/status} for availability and limits and
 * {@code GET /ai/sdk/capabilities} for what the agent can do; see {@code docs/ai-sdk.md}
 * for the frame protocol and the confirm flow.
 *
 * <p>Every route derives identity from the session, so no request body carries a user.
 * The older Gemini routes on {@link AiController} are untouched.
 */
@RestController
@RequestMapping("/ai/sdk")
public class AiSdkController {

    private final AiSdkService sdk;

    public AiSdkController(AiSdkService sdk) {
        this.sdk = sdk;
    }

    // ── Discovery ────────────────────────────────────────────────────────────

    /** Availability and limits. Answers even when the agent is switched off. */
    @GetMapping("/status")
    public ResponseEntity<AiSdkStatusResponse> status() {
        return ResponseEntity.ok(sdk.status());
    }

    /** Reads the agent can perform, mutations it may propose, and the guidance contract. */
    @GetMapping("/capabilities")
    public ResponseEntity<AiSdkCapabilitiesResponse> capabilities() {
        return ResponseEntity.ok(sdk.capabilities());
    }

    // ── Threads ──────────────────────────────────────────────────────────────

    @PostMapping("/threads")
    public ResponseEntity<AiSdkThreadResponse> createThread(
            @Valid @RequestBody(required = false) AiSdkCreateThreadRequest body,
            Authentication authentication) {
        AiSdkCreateThreadRequest payload = body == null ? AiSdkCreateThreadRequest.empty() : body;
        return ResponseEntity.ok(sdk.createThread(authentication.getName(),
                payload.surface(), payload.title(), payload.instructions()));
    }

    @GetMapping("/threads")
    public ResponseEntity<List<AiSdkThreadResponse>> listThreads(
            @RequestParam(required = false) String surface,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant before,
            @RequestParam(required = false) Integer limit,
            Authentication authentication,
            HttpServletRequest request) {
        return ResponseEntity.ok(sdk.listThreads(
                authentication.getName(), surface, before, limit, request.getRemoteAddr()));
    }

    @GetMapping("/threads/{id}")
    public ResponseEntity<AiSdkThreadResponse> getThread(@PathVariable("id") String id,
                                                         Authentication authentication) {
        return ResponseEntity.ok(sdk.getThread(authentication.getName(), id));
    }

    /**
     * Renames a thread, or sets its guidance while it still has none.
     *
     * <p>Guidance is write-once; sending it for a thread that already has some is a 400.
     */
    @PatchMapping("/threads/{id}")
    public ResponseEntity<AiSdkThreadResponse> patchThread(
            @PathVariable("id") String id,
            @Valid @RequestBody(required = false) AiSdkPatchThreadRequest body,
            Authentication authentication) {
        AiSdkPatchThreadRequest payload = body == null ? AiSdkPatchThreadRequest.empty() : body;
        return ResponseEntity.ok(sdk.patchThread(
                authentication.getName(), id, payload.title(), payload.instructions()));
    }

    @DeleteMapping("/threads/{id}")
    public ResponseEntity<Void> deleteThread(@PathVariable("id") String id,
                                             Authentication authentication) {
        sdk.deleteThread(authentication.getName(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/threads/{id}/messages")
    public ResponseEntity<List<AiSdkMessageResponse>> messages(
            @PathVariable("id") String id,
            @RequestParam(required = false) Long after,
            @RequestParam(required = false) Integer limit,
            Authentication authentication,
            HttpServletRequest request) {
        return ResponseEntity.ok(sdk.messages(
                authentication.getName(), id, after, limit, request.getRemoteAddr()));
    }

    @GetMapping("/threads/{id}/actions")
    public ResponseEntity<List<AiSdkActionResponse>> threadActions(@PathVariable("id") String id,
                                                                   Authentication authentication) {
        return ResponseEntity.ok(sdk.pendingActions(authentication.getName(), id));
    }

    // ── Turns ────────────────────────────────────────────────────────────────

    /** Runs a turn and returns the finished answer with every frame it produced. */
    @PostMapping("/turn")
    public ResponseEntity<AiSdkTurnResponse> turn(@Valid @RequestBody AiSdkTurnRequest body,
                                                  Authentication authentication,
                                                  HttpServletRequest request) {
        return ResponseEntity.ok(sdk.turn(
                authentication.getName(), body, request.getRemoteAddr()));
    }

    /**
     * Starts a turn and returns 202; frames arrive live on the user's STOMP queue.
     *
     * <p>Subscribe to the {@code destination} in the response before posting, or the
     * first frames of the turn are missed - they are pushed, not queued for replay.
     */
    @PostMapping("/turn/async")
    public ResponseEntity<AiSdkTurnAcceptedResponse> turnAsync(
            @Valid @RequestBody AiSdkTurnRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        AiSdkTurnAcceptedResponse accepted = sdk.turnAsync(
                authentication.getName(), body, request.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(accepted);
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    /** Prepared actions for the signed-in user across every thread. */
    @GetMapping("/actions")
    public ResponseEntity<List<AiSdkActionResponse>> actions(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String threadId,
            @RequestParam(required = false) Integer limit,
            Authentication authentication) {
        return ResponseEntity.ok(sdk.actions(authentication.getName(), status, threadId, limit));
    }

    @GetMapping("/actions/{id}")
    public ResponseEntity<AiSdkActionResponse> action(@PathVariable("id") String id,
                                                      Authentication authentication) {
        return ResponseEntity.ok(sdk.action(authentication.getName(), id));
    }

    /** Authorises a prepared action. Needs the transaction PIN when the card says so. */
    @PostMapping("/actions/{id}/confirm")
    public ResponseEntity<AiSdkActionResultResponse> confirm(
            @PathVariable("id") String id,
            @Valid @RequestBody(required = false) AiSdkConfirmActionRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        AiSdkConfirmActionRequest payload = body == null ? AiSdkConfirmActionRequest.empty() : body;
        return ResponseEntity.ok(sdk.confirm(
                authentication.getName(), id, payload.pin(), payload.paramsHash(),
                payload.edits(), request.getRemoteAddr()));
    }

    @PostMapping("/actions/{id}/cancel")
    public ResponseEntity<AiSdkActionResultResponse> cancel(@PathVariable("id") String id,
                                                            Authentication authentication) {
        return ResponseEntity.ok(sdk.cancel(authentication.getName(), id));
    }
}
