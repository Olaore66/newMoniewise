package com.moniewise.moniewise_backend.ai.sdk;

import com.moniewise.monnie.api.model.AccountProfileView;
import com.moniewise.monnie.api.model.Money;
import com.moniewise.monnie.api.model.RecipientView;
import com.moniewise.monnie.api.model.SubscriptionView;
import com.moniewise.monnie.api.model.UserRef;
import com.moniewise.monnie.api.model.VasPlanView;
import com.moniewise.monnie.api.model.WalletView;
import com.moniewise.monnie.api.port.AccountReadPort;
import com.moniewise.monnie.api.port.HostPorts;
import com.moniewise.monnie.api.port.RecipientReadPort;
import com.moniewise.monnie.api.port.VasCatalogPort;
import com.moniewise.monnie.api.port.WalletReadPort;
import com.moniewise.moniewise_backend.entity.PayeelordDataPlan;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.repository.BeneficiaryRepository;
import com.moniewise.moniewise_backend.repository.NotificationRepository;
import com.moniewise.moniewise_backend.repository.WalletRepository;
import com.moniewise.moniewise_backend.service.PayeelordVasService;
import com.moniewise.moniewise_backend.service.UserService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Component
public class SdkHostReads {

    private final UserService users;
    private final WalletRepository wallets;
    private final BeneficiaryRepository beneficiaries;
    private final NotificationRepository notifications;
    private final PayeelordVasService vas;

    public SdkHostReads(UserService users, WalletRepository wallets,
                        BeneficiaryRepository beneficiaries, NotificationRepository notifications,
                        PayeelordVasService vas) {
        this.users = users;
        this.wallets = wallets;
        this.beneficiaries = beneficiaries;
        this.notifications = notifications;
        this.vas = vas;
    }

    public HostPorts ports() {
        return HostPorts.of(wallet(), recipients(), account(), catalog());
    }

    private WalletReadPort wallet() {
        return user -> {
            User account = users.findByEmail(user.principal());
            return wallets.findByUserId(account.getId())
                    .map(SdkViews::wallet)
                    .orElse(new WalletView(Money.zeroNgn(), Money.zeroNgn(), "NGN",
                            null, null, WalletView.Status.ACTIVE, null,
                            null, null, null, null, null));
        };
    }

    private RecipientReadPort recipients() {
        return new RecipientReadPort() {
            @Override
            @Transactional(readOnly = true)
            public List<RecipientView> beneficiaries(UserRef user) {
                User account = users.findByEmail(user.principal());
                return beneficiaries.findByUserId(account.getId()).stream()
                        .map(b -> new RecipientView(
                                b.getId(),
                                b.getAlias(),
                                RecipientView.Kind.P2P,
                                null, null, null, null,
                                b.getBeneficiaryUser().getEmail()))
                        .toList();
            }

            @Override
            public Optional<RecipientView> beneficiary(UserRef user, long id) {
                return beneficiaries(user).stream().filter(r -> r.id() == id).findFirst();
            }
        };
    }

    private AccountReadPort account() {
        return new AccountReadPort() {
            @Override
            public AccountProfileView profile(UserRef user) {
                User account = users.findByEmail(user.principal());
                Object first = account.getProfileData() == null ? null : account.getProfileData().get("firstName");
                Object last = account.getProfileData() == null ? null : account.getProfileData().get("lastName");
                return new AccountProfileView(
                        account.getEmail(),
                        first == null ? "" : String.valueOf(first),
                        last == null ? "" : String.valueOf(last),
                        account.getPhone(),
                        account.hasTransactionPin(),
                        account.getBvn() != null);
            }

            @Override
            public boolean hasPin(UserRef user) {
                return users.findByEmail(user.principal()).hasTransactionPin();
            }

            @Override
            public Optional<SubscriptionView> subscription(UserRef user) {
                return Optional.empty();
            }

            @Override
            public int unreadNotificationCount(UserRef user) {
                User account = users.findByEmail(user.principal());
                return (int) notifications.countByUserIdAndIsReadFalse(account.getId());
            }
        };
    }

    private VasCatalogPort catalog() {
        return new VasCatalogPort() {
            @Override
            public List<String> airtimeNetworks() {
                return List.of("MTN", "GLO", "AIRTEL", "9MOBILE");
            }

            @Override
            public Optional<Money> quoteAirtime(String network, Money amount) {
                return Optional.of(amount);
            }

            @Override
            public List<VasPlanView> dataPlans(String network) {
                return vas.getActiveDataPlans().stream()
                        .map(this::plan)
                        .toList();
            }

            @Override
            public Optional<VasPlanView> dataPlan(String planId) {
                return vas.getActiveDataPlans().stream()
                        .filter(p -> planId.equals(p.getDataId()) || planId.equals(String.valueOf(p.getId())))
                        .map(this::plan)
                        .findFirst();
            }

            private VasPlanView plan(PayeelordDataPlan p) {
                return new VasPlanView(
                        p.getDataId() == null ? String.valueOf(p.getId()) : p.getDataId(),
                        p.getNetworkName(),
                        p.getDisplayLabel(),
                        SdkViews.ngn(p.getSellingPrice()),
                        p.getValidityDisplay());
            }
        };
    }
}
