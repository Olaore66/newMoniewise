package com.moniewise.moniewise_backend.thirdParty;

import com.moniewise.moniewise_backend.entity.User;
import java.util.Map;

public interface PaymentGateway {
    Map<String, String> createVirtualAccount(User user);
}
