package dev.gautamgupta.gateway.web;

import io.micrometer.observation.Observation;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.micrometer.tracing.handler.TracingObservationHandler;
import org.slf4j.Logger;
import org.slf4j.MDC;
import reactor.util.context.ContextView;

/** Correlation fields for one chat call. The message never includes prompt or completion text. */
final class RequestCorrelation {

    private RequestCorrelation() {
    }

    static void logChat(Logger log, ContextView context, String model, boolean stream) {
        String requestId = context.getOrDefault(RequestIdThreadLocalAccessor.KEY, "none");
        String traceId = traceId(context);
        MDC.put(RequestIdThreadLocalAccessor.KEY, requestId);
        MDC.put("traceId", traceId);
        try {
            log.info(
                    "chat completions model={} stream={} requestId={} traceId={}",
                    model,
                    stream,
                    requestId,
                    traceId);
        } finally {
            MDC.remove(RequestIdThreadLocalAccessor.KEY);
            MDC.remove("traceId");
        }
    }

    static String traceId(ContextView context) {
        Observation current = context.getOrDefault(ObservationThreadLocalAccessor.KEY, null);
        if (!(current instanceof Observation observation)) {
            return "none";
        }
        TracingObservationHandler.TracingContext tracing = observation.getContext()
                .get(TracingObservationHandler.TracingContext.class);
        if (tracing == null || tracing.getSpan() == null || tracing.getSpan().context() == null) {
            return "none";
        }
        String traceId = tracing.getSpan().context().traceId();
        return traceId == null || traceId.isBlank() ? "none" : traceId;
    }
}
