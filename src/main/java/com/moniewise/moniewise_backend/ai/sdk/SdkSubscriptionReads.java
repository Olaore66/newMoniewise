package com.moniewise.moniewise_backend.ai.sdk;

import com.moniewise.monnie.api.model.SubscriptionView;
import com.moniewise.moniewise_backend.entity.SubscriptionPlan;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.UserSubscription;
import com.moniewise.moniewise_backend.service.SubscriptionService;
import com.moniewise.moniewise_backend.service.UserService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * The user's current plan, for the SDK's account read port.
 *
 * <p>A bean of its own rather than another method on the anonymous {@code AccountReadPort}
 * inside {@link SdkHostReads}: {@code @Transactional} only takes effect through a Spring
 * proxy, and an annotation on an anonymous inner class is silently inert. The plan is a
 * lazy association, so it needs a real transaction open when it is read.
 */
@Component
public class SdkSubscriptionReads {

    private final UserService users;
    private final SubscriptionService subscriptions;

    public SdkSubscriptionReads(UserService users, SubscriptionService subscriptions) {
        this.users = users;
        this.subscriptions = subscriptions;
    }

    @Transactional(readOnly = true)
    public Optional<SubscriptionView> forUser(String email) {
        User account = users.findByEmail(email);
        if (account == null) {
            return Optional.empty();
        }
        return subscriptions.getActiveSubscription(account.getId()).map(SdkSubscriptionReads::view);
    }

    private static SubscriptionView view(UserSubscription subscription) {
        SubscriptionPlan plan = subscription.getPlan();
        return new SubscriptionView(
                plan == null ? null : String.valueOf(plan.getId()),
                plan == null ? null : plan.getPlanName(),
                // getActiveSubscription only ever returns an unexpired ACTIVE row, so the
                // status is known without re-reading it.
                SubscriptionView.Status.ACTIVE,
                plan == null ? null : SdkViews.ngn(plan.getPrice()),
                subscription.getEndDate());
    }
}
