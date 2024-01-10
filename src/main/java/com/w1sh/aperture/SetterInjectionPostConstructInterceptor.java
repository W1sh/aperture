package com.w1sh.aperture;

import com.w1sh.aperture.annotation.Inject;
import com.w1sh.aperture.annotation.Provide;
import com.w1sh.aperture.exception.PostConstructInvocationException;

import javax.annotation.Priority;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

@Provide
@Priority(999)
public class SetterInjectionPostConstructInterceptor implements InvocationInterceptor {

    private final ParameterResolver resolver;

    public SetterInjectionPostConstructInterceptor(ParameterResolver resolver) {this.resolver = resolver;}

    @Override
    public void intercept(Object instance) {
        for (Method method : instance.getClass().getDeclaredMethods()) {
            if (method.isAnnotationPresent(Inject.class)) {
                final var objects = new Object[method.getParameterCount()];
                Parameter[] parameters = method.getParameters();
                for (int i = 0, parametersLength = parameters.length; i < parametersLength; i++) {
                    ResolvableParameterImpl<Object> resolvableParameter = new ResolvableParameterImpl<>(parameters[i]);
                    objects[i] = resolver.resolve(resolvableParameter);
                }
                try {
                    method.invoke(instance, objects);
                } catch (IllegalAccessException | InvocationTargetException e) {
                    throw new PostConstructInvocationException(method, e);
                }
            }
        }
    }

    @Override
    public InvocationType getInterceptorType() {
        return InvocationType.POST_CONSTRUCT;
    }
}
