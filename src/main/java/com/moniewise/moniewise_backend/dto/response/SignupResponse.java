package com.moniewise.moniewise_backend.dto.response;

import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.Wallet;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor

public class SignupResponse {
    private User user;
    private Wallet wallet;
    private List<String> fundingOptions;
    private String virtualAccountNumber;
    private String bankName;

    // Getters
    public User getUser() { return user; }
    public Wallet getWallet() { return wallet; }
    public List<String> getFundingOptions() { return fundingOptions; }
    public String getVirtualAccountNumber() { return virtualAccountNumber; }
    public String getBankName() { return bankName; }
}