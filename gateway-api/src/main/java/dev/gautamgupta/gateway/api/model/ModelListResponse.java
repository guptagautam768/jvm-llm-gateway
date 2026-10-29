package dev.gautamgupta.gateway.api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** {@code GET /v1/models} response. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModelListResponse(String object, List<ModelCard> data) {

    public ModelListResponse {
        data = data == null ? List.of() : List.copyOf(data);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ModelCard(String id, String object, Long created, String ownedBy) {
    }
}
