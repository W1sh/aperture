package com.w1sh.stardust;

import com.w1sh.stardust.InvocationInterceptor.InvocationType;
import com.w1sh.stardust.annotation.Module;
import com.w1sh.stardust.annotation.Primary;
import com.w1sh.stardust.annotation.Provide;
import com.w1sh.stardust.exception.ProviderCandidatesException;
import com.w1sh.stardust.exception.ProviderRegistrationException;
import com.w1sh.stardust.naming.DefaultNamingStrategy;
import com.w1sh.stardust.naming.NamingStrategy;
import com.w1sh.stardust.util.Constructors;
import com.w1sh.stardust.util.Types;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.Annotation;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public abstract class AbstractProviderContainer implements ProviderContainer, InterceptorAware {

    private static final Logger logger = LoggerFactory.getLogger(AbstractProviderContainer.class);

    private final Map<Class<?>, ObjectProvider<?>> providers = new ConcurrentHashMap<>(256);
    private final Map<String, ObjectProvider<?>> named = new ConcurrentHashMap<>(256);
    private final SetValueEnumMap<InvocationType, InvocationInterceptor> interceptors;
    private final NamingStrategy namingStrategy;
    private final ParameterResolver resolver;

    private OverrideStrategy overrideStrategy = OverrideStrategy.ALLOWED;

    protected AbstractProviderContainer(NamingStrategy namingStrategy) {
        this.namingStrategy = namingStrategy;
        this.resolver = new ParameterResolver(this);
        this.interceptors = new SetValueEnumMap<>(InvocationType.class);

        final var containerProvider = new SingletonObjectProvider<>(this);
        providers.put(AbstractProviderContainer.class, containerProvider);
        named.put(namingStrategy.generate(this.getClass()), containerProvider);

        final var namingStrategyProvider = new SingletonObjectProvider<>(this);
        providers.put(NamingStrategy.class, namingStrategyProvider);
        named.put(namingStrategy.generate(namingStrategy.getClass()), namingStrategyProvider);

        final var parameterResolverProvider = new SingletonObjectProvider<>(this);
        providers.put(ParameterResolver.class, parameterResolverProvider);
        named.put(namingStrategy.generate(namingStrategy.getClass()), parameterResolverProvider);

        logger.trace("Container initialization complete. {} internal classes have been registered.", providers.size());
    }

    public static AbstractProviderContainer base() {
        return new DefaultProviderContainer(new DefaultNamingStrategy());
    }

    public void register(Class<?> clazz) {
        Objects.requireNonNull(clazz);
        final var constructor = new ResolvableConstructorImpl<>(Constructors.getInjectConstructor(clazz));
        register(constructor);

        if (clazz.isAnnotationPresent(Module.class)) {
            Object moduleInstance = instance(clazz);
            Arrays.stream(clazz.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(Provide.class))
                    .map(method -> new ResolvableMethodImpl<>(method, moduleInstance))
                    .sorted((Comparator.comparingInt(o -> o.getParameters().size())))
                    .forEach(this::register);
        }
    }

    private void register(ResolvableExecutable<?> executable) {
        Objects.requireNonNull(executable);
        String name = Objects.requireNonNullElse(executable.getName(), namingStrategy.generate(executable.getActualType()));
        logger.debug("Registering provider of class {} with name {}", executable.getActualType().getSimpleName(), name);

        if (OverrideStrategy.NOT_ALLOWED.equals(overrideStrategy) && providers.containsKey(executable.getActualType())) {
            throw ProviderRegistrationException.notAllowedClass(executable.getActualType());
        }
        if (OverrideStrategy.NOT_ALLOWED.equals(overrideStrategy) && named.containsKey(name)) {
            throw ProviderRegistrationException.notAllowedName(name);
        }

        ObjectProvider<?> objProvider = asProvider(executable);
        providers.put(executable.getActualType(), objProvider);
        named.put(name, objProvider);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T instance(Class<T> clazz) {
        Objects.requireNonNull(clazz);
        ObjectProvider<?> provider = get(clazz);
        return provider != null ? (T) provider.singletonInstance() : null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T instance(String name) {
        Objects.requireNonNull(name);
        ObjectProvider<?> provider = named.get(name);
        return provider != null ? (T) provider.singletonInstance() : null;
    }

    @Override
    public <T> T primaryInstance(Class<T> clazz) {
        Objects.requireNonNull(clazz);
        return primaryProvider(clazz).singletonInstance();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> ObjectProvider<T> primaryProvider(Class<T> clazz) {
        Objects.requireNonNull(clazz);
        List<? extends ObjectProvider<?>> primaries = providers.entrySet().stream()
                .filter(entry -> clazz.isAssignableFrom(entry.getKey()))
                .filter(entry -> entry.getKey().isAnnotationPresent(Primary.class))
                .map(Map.Entry::getValue)
                .toList();

        if (primaries.isEmpty()) {
            logger.error("Expected 1 primary candidate but found none for class {}", clazz.getSimpleName());
            throw new ProviderCandidatesException(0, clazz);
        } else if (primaries.size() > 1) {
            logger.error("Expected 1 primary candidate but found {} for class {}", primaries.size(), clazz.getSimpleName());
            throw new ProviderCandidatesException(primaries.size(), clazz);
        }
        return (ObjectProvider<T>) primaries.get(0);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> List<T> instances(Class<T> clazz) {
        Objects.requireNonNull(clazz);
        return (List<T>) candidates(clazz).stream()
                .map(ObjectProvider::singletonInstance)
                .toList();
    }

    @Override
    public <T> ObjectProvider<T> provider(Class<T> clazz) {
        Objects.requireNonNull(clazz);
        return get(clazz);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> ObjectProvider<T> provider(String name) {
        Objects.requireNonNull(name);
        return (ObjectProvider<T>) named.get(name);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> List<ObjectProvider<T>> providers(Class<T> clazz) {
        Objects.requireNonNull(clazz);
        return (List<ObjectProvider<T>>) candidates(clazz);
    }

    @Override
    public <T> boolean contains(Class<T> clazz) {
        return clazz != null && !candidates(clazz).isEmpty();
    }

    @Override
    public boolean contains(String name) {
        return name != null && provider(name) != null;
    }

    @Override
    public OverrideStrategy getOverrideStrategy() {
        return overrideStrategy;
    }

    @Override
    public void setOverrideStrategy(OverrideStrategy strategy) {
        Objects.requireNonNull(strategy);
        this.overrideStrategy = strategy;
    }

    public List<Class<?>> getAllAnnotatedWith(Class<? extends Annotation> annotationType) {
        Objects.requireNonNull(annotationType);
        return providers.keySet().stream()
                .filter(clazz -> clazz.isAnnotationPresent(annotationType))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> get(Class<T> clazz) {
        final var candidates = candidates(clazz);

        if (candidates.size() > 1) {
            logger.error("Expected 1 candidate but found {} for class {}", candidates.size(), clazz.getSimpleName());
            throw new ProviderCandidatesException(candidates.size(), clazz);
        }
        return candidates.isEmpty() ? null : (ObjectProvider<T>) candidates.get(0);
    }

    private <T> List<? extends ObjectProvider<?>> candidates(Class<T> clazz) {
        return providers.entrySet().stream()
                .filter(entry -> clazz.isAssignableFrom(entry.getKey()))
                .map(Map.Entry::getValue)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private <T> T asProvider(ResolvableExecutable<?> executable) {
        if (Scope.SINGLETON.equals(executable.getScope())) {
            Object instance = createInstance(executable);
            return (T) new SingletonObjectProvider<>(instance);
        } else {
            return (T) new PrototypeObjectProvider<>(() -> createInstance(executable));
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T createInstance(ResolvableExecutable<?> executable) {
        final var postConstructInterceptors = interceptors.get(InvocationType.POST_CONSTRUCT);
        Object[] objects = executable.getParameters().stream()
                .map(resolver::resolve)
                .toArray();
        T resolved = (T) executable.resolve(objects);
        postConstructInterceptors.stream()
                .sorted(Comparator.comparing(o -> Types.getPriority(o.getClass())))
                .forEach(invocationInterceptor -> invocationInterceptor.intercept(resolved));
        return resolved;
    }

    @Override
    public void addInterceptor(InvocationInterceptor interceptor) {
        interceptors.put(interceptor.getInterceptorType(), interceptor);
    }

    @Override
    public void removeInterceptor(InvocationInterceptor interceptor) {
        interceptors.remove(interceptor.getInterceptorType(), interceptor);
    }

    @Override
    public void removeAllInterceptors() {
        interceptors.getUnderlyingEnumMap().clear();
    }

    @Override
    public List<InvocationInterceptor> getAllInterceptors() {
        return interceptors.getUnderlyingEnumMap().values()
                .stream()
                .flatMap(Collection::stream)
                .toList();
    }

    @Override
    public List<InvocationInterceptor> getAllInterceptorsOfType(InvocationType type) {
        return List.copyOf(interceptors.get(type));
    }

    public static class DefaultProviderContainer extends AbstractProviderContainer {

        public DefaultProviderContainer(NamingStrategy namingStrategy) {
            super(namingStrategy);
        }
    }
}
