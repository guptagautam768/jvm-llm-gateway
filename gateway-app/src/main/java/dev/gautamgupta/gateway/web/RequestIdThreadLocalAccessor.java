package dev.gautamgupta.gateway.web;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.MDC;

/**
 * Copies the Reactor {@code requestId} context key into the SLF4J MDC so structured
 * logs can carry it across threads. The value is the sanitized request id, never a prompt.
 */
public final class RequestIdThreadLocalAccessor implements ThreadLocalAccessor<String> {

    public static final String KEY = "requestId";

    @Override
    public Object key() {
        return KEY;
    }

    @Override
    public String getValue() {
        return MDC.get(KEY);
    }

    @Override
    public void setValue(String value) {
        if (value == null || value.isBlank()) {
            clear();
            return;
        }
        MDC.put(KEY, value);
    }

    @Override
    public void setValue() {
        clear();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void reset() {
        clear();
    }

    private static void clear() {
        MDC.remove(KEY);
    }

    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            ContextRegistry.getInstance().registerThreadLocalAccessor(new RequestIdThreadLocalAccessor());
        }
    }
}
