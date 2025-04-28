package smartsort;

import java.util.HashMap;
import java.util.Map;

public class ServiceContainer {

    private final Map<Class<?>, Object> services = new HashMap<>();

    public <T> void register(Class<T> type, T implementation) {
        services.put(type, implementation);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> type) {
        return (T) services.get(type);
    }
}
