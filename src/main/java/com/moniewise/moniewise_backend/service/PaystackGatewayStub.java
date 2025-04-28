package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.thirdParty.PaymentGateway;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Service
@Profile("!prod")
public class PaystackGatewayStub implements PaymentGateway {  // Now properly implements the interface

    private static final String[] TEST_BANKS = {
            "MonieWise Test Bank",
            "Virtual Titan Bank",
            "Stub Access Bank"
    };

    @Override  // Now properly overriding interface method
    public Map<String, String> createVirtualAccount(User user) {
        String testAccountNumber = generateTestAccountNumber(user.getId());
        return Map.of(
                "accountNumber", testAccountNumber,
                "bank", TEST_BANKS[ThreadLocalRandom.current().nextInt(TEST_BANKS.length)]
        );
    }

    private String generateTestAccountNumber(Long userId) {
        return String.format("TEST-%d-%05d",
                userId,
                ThreadLocalRandom.current().nextInt(10000, 99999)
        );
    }
}