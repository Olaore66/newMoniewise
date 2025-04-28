package com.moniewise.moniewise_backend.dto.request;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SpendEnvelopeRequest {
    private Long envelopeId;
    private BigDecimal amount;
}
