package com.moniewise.moniewise_backend.dto.request;

import javax.validation.constraints.NotBlank;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OtpRequest {

        @NotBlank
        private String otpCode;
}
