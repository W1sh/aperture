package com.w1sh.wave.core;

public abstract class AbstractApplicationContext implements Configurable {

    private final ComponentRegistry registry;
    private final ComponentScanner scanner;
    private AbstractApplicationEnvironment environment;

    protected AbstractApplicationContext(ComponentRegistry registry, ComponentScanner scanner) {
        this.registry = registry;
        this.scanner = scanner;
    }

    protected AbstractApplicationContext(ComponentRegistry registry, ComponentScanner scanner,
                                         AbstractApplicationEnvironment environment) {
        this(registry, scanner);
        this.environment = environment;
    }

    public abstract <T> T getComponent(Class<T> clazz);

    public abstract <T> T getComponent(Class<T> clazz, String name);

    public abstract boolean containsComponent(Class<?> clazz);

    public abstract boolean containsComponent(String name);

    public abstract Class<?> getType(String name);

    public abstract boolean isTypeMatch(String name, Class<?> clazz);

    public abstract void initialize();

    public abstract void refresh();

    public abstract void clear();

    public AbstractApplicationEnvironment getEnvironment() {
        return environment;
    }

    public void setEnvironment(AbstractApplicationEnvironment environment) {
        this.environment = environment;
        this.registry.setEnvironment(environment);
    }

    public ComponentRegistry getRegistry() {
        return registry;
    }

    public ComponentScanner getScanner() {
        return scanner;
    }
}
