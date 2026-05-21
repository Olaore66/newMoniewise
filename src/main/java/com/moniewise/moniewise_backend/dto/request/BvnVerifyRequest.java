package com.moniewise.moniewise_backend.dto.request;

import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

@Getter
@Setter
public class BvnVerifyRequest {

    @NotBlank(message = "BVN is required")
    @Pattern(regexp = "\\d{11}", message = "BVN must be exactly 11 digits")
    private String bvn;
    // No phone / email fields — the backend reads those from the authenticated
    // user's record (stored at signup).  The client only submits the BVN.
}
