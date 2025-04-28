package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.dto.response.TncResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/tnc")
public class TncController {

    @GetMapping
    public ResponseEntity<?> getTnc() {
        TncResponse tnc = new TncResponse(
                "Moniewise helps you budget with discipline. Charges: ₦100 per 30 days, 5% on Emergency withdrawals, 2% on transfers (Safe Lock transfers free). We protect your data and don’t share bank details.",
                "1.0"
        );
        return ResponseEntity.ok(tnc);
    }
}
