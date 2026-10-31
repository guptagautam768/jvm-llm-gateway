package dev.gautamgupta.gateway.web;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import reactor.core.publisher.Mono;

/** Opens one observation around a reactive step and keeps it in the Reactor context. */
public final class Spans {

    private Spans() {
    }

    public static <T> Mono<T> mono(ObservationRegistry registry, String name, Mono<T> mono) {
        ObservationRegistry actual = registry == null ? ObservationRegistry.NOOP : registry;
        return Mono.defer(() -> {
            Observation observation = Observation.start(name, actual);
            return mono.doOnError(observation::error)
                    .doFinally(signal -> observation.stop())
                    .contextWrite(context -> context.put(ObservationThreadLocalAccessor.KEY, observation));
        });
    }

    public static Observation start(ObservationRegistry registry, String name) {
        ObservationRegistry actual = registry == null ? ObservationRegistry.NOOP : registry;
        return Observation.start(name, actual);
    }
}
