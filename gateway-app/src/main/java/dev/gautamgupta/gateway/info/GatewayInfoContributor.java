package dev.gautamgupta.gateway.info;

import dev.gautamgupta.gateway.cache.CacheModule;
import dev.gautamgupta.gateway.guardrails.GuardrailsModule;
import dev.gautamgupta.gateway.persistence.PersistenceModule;
import dev.gautamgupta.gateway.tenancy.TenancyModule;
import java.util.List;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

@Component
public class GatewayInfoContributor implements InfoContributor {

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("modules", List.of(
                "gateway-api",
                "gateway-core",
                "gateway-providers",
                CacheModule.ID,
                TenancyModule.ID,
                PersistenceModule.ID,
                GuardrailsModule.ID,
                "gateway-app"));
    }
}
