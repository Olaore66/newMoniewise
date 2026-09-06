package com.moniewise.moniewise_backend.dto.request;

import javax.validation.constraints.Size;

import java.util.Map;

/**
 * Authorises a prepared action.
 *
 * @param pin the transaction PIN. Required whenever the action reports
 *     {@code requiresPin}; verified against the user's own PIN before anything executes
 * @param paramsHash the hash from the card the user saw. Binds the confirmation to those
 *     exact figures, so a client cannot change an amount without changing the hash
 * @param edits changes to fields the card marked editable. An edit to any other field is
 *     rejected
 */
public record AiSdkConfirmActionRequest(
        @Size(max = 12) String pin,
        @Size(max = 128) String paramsHash,
        Map<String, Object> edits
) {
    public static AiSdkConfirmActionRequest empty() {
        return new AiSdkConfirmActionRequest(null, null, null);
    }
}
