package dev.gautamgupta.gateway.core.provider;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Id to adapter lookup. A duplicate id fails startup rather than silently replacing an adapter. */
public final class ProviderRegistry {

    private final Map<String, ProviderClient> providers;

    public ProviderRegistry(Collection<ProviderClient> clients) {
        Map<String, ProviderClient> map = new LinkedHashMap<>();
        for (ProviderClient client : clients) {
            ProviderClient previous = map.put(client.id(), client);
            if (previous != null) {
                throw new IllegalStateException("Duplicate provider id: " + client.id());
            }
        }
        this.providers = Map.copyOf(map);
    }

    public ProviderClient get(String id) {
        ProviderClient client = providers.get(id);
        if (client == null) {
            throw new IllegalArgumentException("Unknown provider: " + id);
        }
        return client;
    }

    public Set<String> ids() {
        return providers.keySet();
    }
}
