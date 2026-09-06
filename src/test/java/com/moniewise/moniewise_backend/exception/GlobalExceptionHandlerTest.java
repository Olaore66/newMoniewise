package com.moniewise.moniewise_backend.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void anExplicitStatusSurvivesToTheClient() {
        // Before this handler existed these fell through to the catch-all, so a
        // deliberate 409 reached the caller as a 500 with a correlation id - and the
        // client could not tell "you already confirmed this" from "the server broke".
        ResponseEntity<Map<String, Object>> response = handler.handleResponseStatus(
                new ResponseStatusException(HttpStatus.CONFLICT, "That is already in flight."));

        assertEquals(409, response.getStatusCodeValue());
        assertEquals("That is already in flight.", response.getBody().get("message"));
        assertEquals(409, response.getBody().get("status"));
    }

    @Test
    void aMissingReasonFallsBackToTheStatusPhrase() {
        // getReason() is nullable and Map.of rejects nulls, so this is the case that
        // would otherwise throw inside the error handler itself.
        ResponseEntity<Map<String, Object>> response = handler.handleResponseStatus(
                new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));

        assertEquals(503, response.getStatusCodeValue());
        assertEquals("Service Unavailable", response.getBody().get("message"));
    }
}
