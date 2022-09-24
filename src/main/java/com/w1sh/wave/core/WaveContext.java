package com.w1sh.wave.core;

import com.w1sh.wave.core.annotation.Inject;
import com.w1sh.wave.core.annotation.Qualifier;
import com.w1sh.wave.core.binding.Lazy;
import com.w1sh.wave.core.binding.LazyBinding;
import com.w1sh.wave.core.binding.Provider;
import com.w1sh.wave.core.binding.ProviderBinding;
import com.w1sh.wave.core.builder.ContextBuilder;
import com.w1sh.wave.core.builder.ContextGroup;
import com.w1sh.wave.core.builder.Options;
import com.w1sh.wave.core.exception.CircularDependencyException;
import com.w1sh.wave.core.exception.ComponentCreationException;
import com.w1sh.wave.core.exception.UnsatisfiedComponentException;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import java.lang.reflect.*;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class WaveContext {

    private static final Logger logger = LoggerFactory.getLogger(WaveContext.class);

    private final Map<Class<?>, ObjectProvider<?>> providers = new ConcurrentHashMap<>(256);
    private final Map<String, ObjectProvider<?>> named = new ConcurrentHashMap<>(256);
    private Set<String> activeProfiles = new HashSet<>(8);
    private NamingStrategy namingStrategy = new SimpleNamingStrategy();

    public WaveContext namingStrategy(NamingStrategy namingStrategy) {
        this.namingStrategy = namingStrategy;
        return this;
    }

    public WaveContext activeProfiles(String... profiles) {
        this.activeProfiles = Set.of(profiles);
        return this;
    }

    public void context(ContextGroup contextGroup) {
        ContextBuilder.setStaticContext(this);
        contextGroup.apply();
        ContextBuilder.clearStaticContext();
    }

    public void registerProvider(@NotNull Class<?> clazz) {
        final Set<Class<?>> initializationChain = new HashSet<>();
        initializationChain.add(clazz);

        final ObjectProvider<?> objectProvider = createObjectProvider(clazz, initializationChain);
        providers.put(clazz, objectProvider);
    }

    public void registerProvider(@NotNull Class<?> clazz, @NotNull Supplier<?> supplier) {
        final ObjectProvider<?> objectProvider = new SimpleObjectProvider<>(supplier);
        providers.put(clazz, objectProvider);
    }

    public void registerSingleton(@NotNull Object instance, Options options) {
        if (!isContainedWithinActiveProfiles(options)) {
            logger.debug("Skipping registration of   instance of class {}", instance.getClass().getSimpleName());
            return;
        }
        final ObjectProvider<?> objectProvider = new DefinedObjectProvider<>(instance);
        final String singletonName = isNameDefined(options) ? options.getName() : namingStrategy.generate(instance.getClass());
        providers.put(instance.getClass(), objectProvider);
        named.put(singletonName, objectProvider);
    }

    public void registerSingleton(@NotNull Class<?> clazz, Options options) {
        if (!isContainedWithinActiveProfiles(options)) {
            logger.debug("Skipping registration of class {}", clazz.getSimpleName());
            return;
        }
        final Set<Class<?>> initializationChain = new HashSet<>();
        initializationChain.add(clazz);

        final var instance = createInstance(clazz, initializationChain);
        processPostConstructorMethods(instance);
        final ObjectProvider<?> objectProvider = new DefinedObjectProvider<>(instance);
        final String singletonName = isNameDefined(options) ? options.getName() : namingStrategy.generate(instance.getClass());
        providers.put(clazz, objectProvider);
        named.put(singletonName, objectProvider);
    }

    @SuppressWarnings("unchecked")
    public <T> T instance(Class<T> clazz) {
        final ObjectProvider<T> provider = (ObjectProvider<T>) providers.get(clazz);
        if (provider == null) {
            logger.error("No component candidate found for class {}", clazz);
            throw new UnsatisfiedComponentException("No component candidate found for class " + clazz);
        }
        return provider.singletonInstance();
    }

    @SuppressWarnings("unchecked")
    public <T> T instanceOrNull(Class<T> clazz) {
        return providers.get(clazz) != null ? (T) providers.get(clazz).singletonInstance() : null;
    }

    public Object instance(String name) {
        final ObjectProvider<?> namedProvider = named.get(name);
        if (namedProvider == null) {
            logger.error("No component candidate found for name {}", name);
            throw new UnsatisfiedComponentException("No component candidate found for name " + name);
        }
        return namedProvider.singletonInstance();
    }

    public Object instanceOrNull(String name) {
        return named.get(name) != null ? named.get(name).singletonInstance() : null;
    }

    @SuppressWarnings("unchecked")
    public <T> ObjectProvider<T> provider(Class<T> clazz) {
        return (ObjectProvider<T>) providers.get(clazz);
    }

    public ObjectProvider<?> provider(String name) {
        return named.get(name);
    }

    @SuppressWarnings("unchecked")
    public <T> ObjectProvider<T> getProvider(Class<T> clazz, boolean nullable) {
        final List<ObjectProvider<T>> candidates = new ArrayList<>();
        for (Entry<Class<?>, ObjectProvider<?>> scopeClazz : providers.entrySet()) {
            if (clazz.isAssignableFrom(scopeClazz.getKey())) {
                candidates.add((ObjectProvider<T>) scopeClazz.getValue());
            }
        }

        if (candidates.isEmpty()) {
            if (nullable) return null;
            logger.error("No injection candidate found for class {}", clazz);
            throw new UnsatisfiedComponentException("No injection candidate found for class " + clazz);
        } else if (candidates.size() > 1) {
            logger.error("Multiple injection candidates found for class {}", clazz);
            throw new UnsatisfiedComponentException("Multiple injection candidates found for class " + clazz);
        }
        return candidates.get(0);
    }

    @SuppressWarnings("unchecked")
    public <T> ObjectProvider<T> getProvider(String name, boolean nullable) {
        final List<ObjectProvider<T>> candidates = new ArrayList<>();
        for (Entry<String, ObjectProvider<?>> scopeClazz : named.entrySet()) {
            if (scopeClazz.getKey().equalsIgnoreCase(name)) {
                candidates.add((ObjectProvider<T>) scopeClazz.getValue());
            }
        }

        if (candidates.isEmpty()) {
            if (nullable) return null;
            logger.error("No injection candidate found for qualifier {}", name);
            throw new UnsatisfiedComponentException("No injection candidate found for qualifier " + name);
        } else if (candidates.size() > 1) {
            logger.error("Multiple injection candidates found for qualifier {}", name);
            throw new UnsatisfiedComponentException("Multiple injection candidates found for qualifier " + name);
        }
        return candidates.get(0);
    }

    @SuppressWarnings("unchecked")
    protected <T> Constructor<T> findInjectAnnotatedConstructor(Class<T> aClass) {
        Constructor<T> injectConstructor = null;
        for (Constructor<?> constructor : aClass.getDeclaredConstructors()) {
            if (constructor.isAnnotationPresent(Inject.class)) {
                if (injectConstructor != null) throw new ComponentCreationException(String.format(
                        "%s has multiple constructors annotated with @Inject", aClass.getName()));
                injectConstructor = (Constructor<T>) constructor;
            }
        }

        if (injectConstructor == null) {
            try {
                return aClass.getConstructor();
            } catch (NoSuchMethodException e) {
                throw new ComponentCreationException(String.format(
                        "%s doesn't have a constructor annotated with @Inject or a no-arg constructor", aClass.getName()));
            }
        }
        return injectConstructor;
    }

    /**
     * Invokes all the methods annotated with {@link javax.annotation.PostConstruct} annotation for the given instance.
     *
     * @param instance The object instance to invoke the methods on.
     */
    protected void processPostConstructorMethods(Object instance) {
        final Deque<Method> postConstructMethods = new LinkedList<>();
        for (Class<?> clazz = instance.getClass(); clazz != Object.class; clazz = clazz.getSuperclass()) {
            for (Method method : clazz.getDeclaredMethods()) {
                if (method.isAnnotationPresent(PostConstruct.class)) {
                    postConstructMethods.addFirst(method);
                }
            }
        }

        for (Method m : postConstructMethods) {
            try {
                m.invoke(instance);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new ComponentCreationException(String.format(
                        "Can't invoke @PostConstruct annotated method %s:%s", m.getClass(), m.getName()), e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    protected <T> ObjectProvider<T> createObjectProvider(Class<T> clazz, Set<Class<?>> chain) {
        return new SimpleObjectProvider<>(() -> {
            final var instance = createInstance(clazz, chain);
            processPostConstructorMethods(instance);
            return (T) instance;
        });
    }

    private <T> Object createInstance(Class<T> clazz, Set<Class<?>> chain) {
        final Constructor<T> constructor = findInjectAnnotatedConstructor(clazz);
        if (constructor.getParameterTypes().length == 0) {
            logger.debug("Creating new instance of class {}", clazz.getName());
            return newInstance(constructor, new Object[]{});
        }

        if (chain.contains(clazz)) {
            throw new CircularDependencyException(String.format("Can't create instance of class %s. Circular dependency: %s",
                    clazz.getName(), createCircularDependencyChain(chain, clazz)));
        }

        final Object[] params = new Object[constructor.getParameterTypes().length];
        for (int i = 0; i < constructor.getParameterTypes().length; i++) {
            final Type paramType = constructor.getGenericParameterTypes()[i];
            final Class<?> actualParameterType = getActualParameterType(paramType);
            final String qualifier = getParameterQualifier(constructor.getParameters()[i]);
            final ObjectProvider<?> provider = qualifier != null ?
                    getProvider(qualifier, false) : getProvider(actualParameterType, false);

            if (provider == null) {
                logger.info("No candidate found for required parameter {}. Registering for initialization.", actualParameterType.getName());
                registerSingleton(actualParameterType, Options.builder().withName(qualifier));
                params[i] = getProvider(actualParameterType, false);
            } else {
                if (isWrappedInBinding(paramType)) {
                    params[i] = createBinding((ParameterizedType) paramType);
                } else {
                    params[i] = provider.singletonInstance();
                }
            }
        }
        return newInstance(constructor, params);
    }

    private Class<?> getActualParameterType(Type paramType) {
        return isWrappedInBinding(paramType) ? (Class<?>) ((ParameterizedType) paramType).getActualTypeArguments()[0] : (Class<?>) paramType;
    }

    private String getParameterQualifier(Parameter parameter) {
        if (parameter.isAnnotationPresent(Qualifier.class) && !parameter.getAnnotation(Qualifier.class).name().isBlank()) {
            return parameter.getAnnotation(Qualifier.class).name();
        } else return null;
    }

    private boolean isWrappedInBinding(Type type) {
        if (type instanceof ParameterizedType parameterizedType) {
            return parameterizedType.getRawType().equals(Lazy.class) || parameterizedType.getRawType().equals(Provider.class);
        }
        return false;
    }

    private Object createBinding(ParameterizedType type) {
        final Class<?> parameterizedClazz = (Class<?>) type.getActualTypeArguments()[0];
        final ObjectProvider<?> provider = getProvider(parameterizedClazz, false);
        if (type.getRawType().equals(Lazy.class)) {
            return new LazyBinding<>(provider);
        }
        if (type.getRawType().equals(Provider.class)) {
            return new ProviderBinding<>(provider);
        }
        throw new ComponentCreationException(String.format("Unknown binding type %s", type.getRawType()));
    }

    private String createCircularDependencyChain(Set<Class<?>> chain, Class<?> clazz) {
        return chain.stream()
                .map(chainClazz -> chainClazz.getSimpleName() + " -> ")
                .collect(Collectors.joining("", "", clazz.getSimpleName()));
    }

    private <T> T newInstance(Constructor<T> constructor, Object[] params) {
        try {
            return constructor.newInstance(params);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new ComponentCreationException("Unable to create an instance of the class", e);
        }
    }

    private boolean isContainedWithinActiveProfiles(Options options) {
        if (activeProfiles.isEmpty()) return true;
        return options != null && options.getProfiles() != null && Arrays.stream(options.getProfiles())
                .anyMatch(element -> activeProfiles.contains(element));
    }

    private boolean isNameDefined(Options options) {
        return options != null && options.getName() != null && !options.getName().isBlank();
    }

    public NamingStrategy getNamingStrategy() {
        return namingStrategy;
    }
}
