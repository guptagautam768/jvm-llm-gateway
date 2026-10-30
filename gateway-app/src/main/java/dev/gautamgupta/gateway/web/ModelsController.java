package dev.gautamgupta.gateway.web;

import dev.gautamgupta.gateway.api.model.ModelListResponse;
import dev.gautamgupta.gateway.core.chat.ChatCompletionService;
import dev.gautamgupta.gateway.metrics.GatewayMetrics;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class ModelsController {

    private final ChatCompletionService service;
    private final GatewayMetrics metrics;

    public ModelsController(ChatCompletionService service, GatewayMetrics metrics) {
        this.service = service;
        this.metrics = metrics;
    }

    @GetMapping("/v1/models")
    public Mono<ModelListResponse> list() {
        long start = System.nanoTime();
        return service.listModels()
                .doOnNext(ignored -> metrics.recordSuccess("models", "gateway", "catalog", null, start, null))
                .doOnError(error -> metrics.recordError("models", "gateway", "catalog", error, start, null));
    }
}
