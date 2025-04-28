package com.moniewise.moniewise_backend.thirdParty;

public interface NotificationProvider {
    String sendNotification(String userId, String message);
}
