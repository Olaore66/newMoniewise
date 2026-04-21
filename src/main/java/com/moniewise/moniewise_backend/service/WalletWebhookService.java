package com.moniewise.moniewise_backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class WalletWebhookService {

    private static final Logger logger = LoggerFactory.getLogger(WalletWebhookService.class);
    private final WalletService walletService;

    public WalletWebhookService(WalletService walletService) {
        this.walletService = walletService;
    }

    public void processFundingWebhook(String payloadJson) {
        try {
            logger.info("Delegating validated funding webhook to WalletService");
            walletService.fundWalletFromWebhook(payloadJson);
        } catch (Exception e) {
            logger.error("Wallet webhook processing failed", e);
            throw new RuntimeException("Webhook processing failed", e);
        }
    }
}
