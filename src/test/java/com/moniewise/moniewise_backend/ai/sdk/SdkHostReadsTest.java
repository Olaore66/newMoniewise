package com.moniewise.moniewise_backend.ai.sdk;

import com.moniewise.monnie.api.model.VasPlanView;
import com.moniewise.monnie.api.port.HostPorts;
import com.moniewise.moniewise_backend.entity.PayeelordDataPlan;
import com.moniewise.moniewise_backend.repository.BeneficiaryRepository;
import com.moniewise.moniewise_backend.repository.NotificationRepository;
import com.moniewise.moniewise_backend.repository.WalletRepository;
import com.moniewise.moniewise_backend.service.PayeelordVasService;
import com.moniewise.moniewise_backend.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SdkHostReadsTest {

    private PayeelordVasService vas;
    private HostPorts ports;

    @BeforeEach
    void setUp() {
        vas = mock(PayeelordVasService.class);
        SdkHostReads reads = new SdkHostReads(
                mock(UserService.class),
                mock(WalletRepository.class),
                mock(BeneficiaryRepository.class),
                mock(NotificationRepository.class),
                vas,
                mock(SdkSubscriptionReads.class));
        ports = reads.ports();
        when(vas.getActiveDataPlans()).thenReturn(List.of(
                plan("1", "MTN", "1GB Monthly", "500"),
                plan("2", "GLO", "2GB Monthly", "700")));
    }

    @Test
    void dataPlansFilterByNetworkName() {
        // The argument used to be ignored, which offered a Glo bundle to someone asking
        // about their MTN line.
        List<VasPlanView> plans = ports.vas().dataPlans("mtn");

        assertEquals(1, plans.size());
        assertEquals("MTN", plans.get(0).network());
    }

    @Test
    void dataPlansAlsoMatchTheProvidersNetworkId() {
        // A caller cannot know whether it holds the display name or the provider's id.
        List<VasPlanView> plans = ports.vas().dataPlans("2");

        assertEquals(1, plans.size());
        assertEquals("GLO", plans.get(0).network());
    }

    @Test
    void aBlankNetworkStillReturnsTheWholeCatalogue() {
        assertEquals(2, ports.vas().dataPlans("").size());
        assertEquals(2, ports.vas().dataPlans(null).size());
    }

    @Test
    void anUnknownNetworkReturnsNothingRatherThanEverything() {
        assertTrue(ports.vas().dataPlans("STARLINK").isEmpty());
    }

    private static PayeelordDataPlan plan(String networkId, String networkName, String name,
                                          String cost) {
        PayeelordDataPlan plan = new PayeelordDataPlan();
        plan.setNetworkId(networkId);
        plan.setNetworkName(networkName);
        plan.setDataId("d-" + networkId);
        plan.setPlanName(name);
        plan.setSizeLabel("1GB");
        plan.setValidityLabel("30 days");
        plan.setCostPrice(new BigDecimal(cost));
        plan.setMarkupAmount(BigDecimal.ZERO);
        plan.setActive(true);
        return plan;
    }
}
