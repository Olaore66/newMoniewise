package com.moniewise.moniewise_backend.psp;

import com.moniewise.moniewise_backend.entity.Wallet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class PaymentGatewayResolver {

    private final Map<String, PaymentGateway> gatewaysByProviderName;
    private final String defaultProviderName;

    public PaymentGatewayResolver(
            List<PaymentGateway> gateways,
            @Value("${moniewise.psp.default-provider:SECUREWAVE}") String defaultProviderName
    ) {
        this.gatewaysByProviderName = gateways.stream()
                .collect(Collectors.toMap(
                        gateway -> normalize(gateway.getProviderName()),
                        Function.identity()
                ));
        this.defaultProviderName = normalize(defaultProviderName);
    }

    public PaymentGateway resolveDefault() {
        return resolveByProviderName(defaultProviderName);
    }

    public PaymentGateway resolveByProviderName(String providerName) {
        String normalizedName = normalize(providerName);
        PaymentGateway gateway = gatewaysByProviderName.get(normalizedName);
        if (gateway != null) {
            return gateway;
        }

        PaymentGateway fallback = gatewaysByProviderName.get(defaultProviderName);
        if (fallback == null) {
            throw new IllegalStateException("No payment gateway configured for provider: " + providerName);
        }
        return fallback;
    }

    public PaymentGateway resolveForWallet(Wallet wallet) {
        if (wallet == null || wallet.getProviderName() == null || wallet.getProviderName().isBlank()) {
            return resolveDefault();
        }
        return resolveByProviderName(wallet.getProviderName());
    }

    private String normalize(String providerName) {
        if (providerName == null || providerName.isBlank()) {
            return "";
        }
        return providerName.trim().toUpperCase(Locale.ROOT);
    }
}
