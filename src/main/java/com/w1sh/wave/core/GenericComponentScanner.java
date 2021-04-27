package com.w1sh.wave.core;

import com.w1sh.wave.condition.FilteringConditionalProcessor;
import com.w1sh.wave.condition.GenericFilteringConditionalProcessor;
import com.w1sh.wave.core.annotation.Component;
import com.w1sh.wave.core.annotation.Configuration;
import com.w1sh.wave.core.annotation.Provides;
import org.reflections.Reflections;
import org.reflections.scanners.MethodAnnotationsScanner;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.scanners.TypeAnnotationsScanner;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

import static org.reflections.ReflectionUtils.getAllMethods;
import static org.reflections.ReflectionUtils.withAnnotation;

public class GenericComponentScanner implements ComponentScanner {

    private static final Logger logger = LoggerFactory.getLogger(GenericComponentScanner.class);

    private final FilteringConditionalProcessor conditionProcessor;
    private final Reflections reflections;
    private final String packagePrefix;

    public GenericComponentScanner(FilteringConditionalProcessor conditionProcessor, String packagePrefix) {
        this.reflections = new Reflections(new ConfigurationBuilder()
                .setUrls(ClasspathHelper.forPackage(packagePrefix))
                .setScanners(new TypeAnnotationsScanner(), new SubTypesScanner(), new MethodAnnotationsScanner())
                .useParallelExecutor());
        this.packagePrefix = packagePrefix;
        this.conditionProcessor = conditionProcessor != null ? conditionProcessor : new GenericFilteringConditionalProcessor(reflections);
    }

    public GenericComponentScanner(String packagePrefix) {
        this(null, packagePrefix);
    }

    @Override
    public Set<Class<?>> scanClasses() {
        logger.debug("Scanning in defined package \"{}\" for annotated classes", packagePrefix);
        final Set<Class<?>> componentClasses = reflections.getTypesAnnotatedWith(Component.class);
        return conditionProcessor.processConditionals(componentClasses);
    }

    @Override
    public Set<Method> scanMethods() {
        logger.debug("Scanning in defined package \"{}\" for annotated methods", packagePrefix);
        final Set<Class<?>> configurationClasses = reflections.getTypesAnnotatedWith(Configuration.class);
        return configurationClasses.stream()
                .map(clazz -> getAllMethods(clazz, withAnnotation(Provides.class)))
                .flatMap(Collection::stream)
                .collect(Collectors.toSet());
    }
}
