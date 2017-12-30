package com.riguz.forks.ioc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Provider;
import javax.inject.Singleton;

import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class Injector {

    private final ConcurrentMap<InjectQualifier<?>, Object> singletons = new ConcurrentHashMap<>();
    private final ConcurrentMap<InjectQualifier<?>, Provider<?>> providers = new ConcurrentHashMap<>();
    private final ConcurrentMap<Class<?>, List<InjectField>> injectFieldsCache = new ConcurrentHashMap<>();

    private final Logger logger = LoggerFactory.getLogger(Injector.class);

    public Injector() {

    }

    public Injector(final Iterable<?> modules) {
        for (final Object module : modules) {
            if (module instanceof Class) {
                throw new IllegalArgumentException(
                    "Please use an instance instead of Class:" + ((Class<?>) module).getName());
            }
            Set<Method> providesMethods = Helper.getProviders(module.getClass());
            for (Method method : providesMethods) {
                this.addProvidesFromModule(module, method);
            }
        }
    }

    public Injector(Object... modules) {
        this(Arrays.asList(modules));
    }

    public <T> Provider<T> getProvider(final InjectQualifier<T> injectQualifier) {
        return this.getOrCreateProvider(injectQualifier, null);
    }

    public <T> Provider<T> getProvider(Class<T> type) {
        return this.getOrCreateProvider(new InjectQualifier<>(type), null);
    }

    /**
     * Get instance by class and qualifier
     */
    public <T> T getInstance(final InjectQualifier<T> injectQualifier) {
        return this.getOrCreateProvider(injectQualifier, null).get();
    }

    /**
     * Get instance by type
     */
    public <T> T getInstance(Class<T> type) {
        return this.getOrCreateProvider(new InjectQualifier<T>(type), null).get();
    }

    /**
     * Add providers from config module
     */
    protected void addProvidesFromModule(final Object module, final Method method) {
        final InjectQualifier<?> injectQualifier = InjectQualifier
            .of(method.getReturnType(), Helper.getQualifier(method.getAnnotations()));
        if (this.providers.containsKey(injectQualifier)) {
            throw new RuntimeException("Multiple providers found for:" + injectQualifier.toString());
        }
        boolean isSingleton = Helper.isSingletonProvider(method);
        final Provider<?>[] paramProviders = this.createParamProviders(
            injectQualifier, method, Collections.singleton(injectQualifier));
        Provider<?> provider = this.createProvider(injectQualifier, module, method, paramProviders, isSingleton);
        this.providers.put(injectQualifier, provider);
    }

    /**
     * Create singleton instance
     */
    protected <T> void createSingletonIfNotExists(final InjectQualifier<T> injectQualifier, Constructor<T> constructor,
        final Provider<?>[] paramProviders) {
        if (!this.singletons.containsKey(injectQualifier)) {
            synchronized (this.singletons) {
                if (!this.singletons.containsKey(injectQualifier)) {
                    T instance = Helper.createNewInstance(injectQualifier, constructor, paramProviders);
                    this.singletons.put(injectQualifier, instance);
                }
            }
        }
    }

    /**
     * Create singleton instance from method
     */
    protected <T> void createSingletonIfNotExists(final InjectQualifier<T> injectQualifier, Object module,
        Method method, final Provider<?>[] paramProviders) {
        if (!Injector.this.singletons.containsKey(injectQualifier)) {
            synchronized (Injector.this.singletons) {
                if (!Injector.this.singletons.containsKey(injectQualifier)) {
                    T instance = Helper.createNewInstance(injectQualifier, module, method, paramProviders);
                    Injector.this.singletons.put(injectQualifier, instance);
                }
            }
        }
    }

    /**
     * Create a provider for instance
     *
     * @param injectQualifier inject target
     * @param constructor inject constructor
     * @param paramProviders constructor parameter providers
     * @param isSingleton the inject target is singleton
     */
    protected <T> Provider<T> createProvider(final InjectQualifier<T> injectQualifier, Constructor<T> constructor,
        final Provider<?>[] paramProviders, boolean isSingleton) {
        return new Provider<T>() {
            @SuppressWarnings("unchecked")
            @Override
            public T get() {
                if (isSingleton) {
                    createSingletonIfNotExists(injectQualifier, constructor, paramProviders);
                    return (T) Injector.this.singletons.get(injectQualifier);
                }
                return Helper.createNewInstance(injectQualifier, constructor, paramProviders);
            }
        };
    }

    /**
     * Create provider from provide method
     */
    protected <T> Provider<T> createProvider(final InjectQualifier<T> injectQualifier, Object module, Method method,
        final Provider<?>[] paramProviders, boolean isSingleton) {
        return new Provider<T>() {
            @SuppressWarnings("unchecked")
            @Override
            public T get() {
                if (isSingleton) {
                    createSingletonIfNotExists(injectQualifier, module, method, paramProviders);
                    return (T) Injector.this.singletons.get(injectQualifier);
                }
                // or create new instance each time
                return Helper.createNewInstance(injectQualifier, module, method, paramProviders);
            }
        };
    }

    /**
     * Get or create provider with dependencies
     */
    @SuppressWarnings("unchecked")
    protected <T> Provider<T> getOrCreateProvider(final InjectQualifier<T> injectQualifier,
        final Set<InjectQualifier<?>> chain) {
        if (this.providers.containsKey(injectQualifier)) {
            return (Provider<T>) this.providers.get(injectQualifier);
        }
        final Constructor<T> constructor = (Constructor<T>) Helper.getInjectConstructor(injectQualifier.targetClass);
        final Provider<?>[] paramProviders = createParamProviders(injectQualifier, constructor, chain);

        boolean isSingleton = injectQualifier.targetClass.isAnnotationPresent(Singleton.class);
        return createProvider(injectQualifier, constructor, paramProviders, isSingleton);
    }

    /**
     * Create providers for inject constructor
     *
     * @param injectQualifier inject class, eg. A(B b, C c)
     * @param paramClasses constructor parameter classes, eg.[B.class, C.class]
     * @param paramTypes constructor parameter generic types, eg.[A, B]
     * @param annotations constructor parameter annotations, eg[@Singleton, ...]
     * @param chain targets to be injected, eg. [A, B, C]
     */
    protected Provider<?>[] createParamProviders(final InjectQualifier<?> injectQualifier,
        final Class<?>[] paramClasses,
        final Type[] paramTypes,
        final Annotation[][] annotations,
        final Set<InjectQualifier<?>> chain) {
        Provider<?>[] providers = new Provider<?>[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            Class<?> paramClass = paramClasses[i];
            Annotation qualifier = Helper.getQualifier(annotations[i]);

            if (Provider.class.equals(paramClass)) {
                // if the argument is a provider
                Class<?> providerType = (Class<?>) ((ParameterizedType) paramTypes[i]).getActualTypeArguments()[0];
                final InjectQualifier<?> newInjectQualifier = InjectQualifier.of(providerType, qualifier);
                providers[i] = this.getOrCreateProvider(newInjectQualifier, null);
            } else {
                // a commom type
                final InjectQualifier<?> paramInjectQualifier = InjectQualifier.of(paramClass, qualifier);
                final Set<InjectQualifier<?>> dependencyChain = Helper.appendChain(chain, injectQualifier);
                if (dependencyChain.contains(paramInjectQualifier)) {
                    throw new RuntimeException("Circular dependency found:" + Helper.printChain(dependencyChain,
                        paramInjectQualifier));
                }
                providers[i] = this.getOrCreateProvider(paramInjectQualifier, dependencyChain);
            }
        }
        return providers;
    }

    protected Provider<?>[] createParamProviders(final InjectQualifier<?> injectQualifier, final Method providerMethod,
        Set<InjectQualifier<?>> chain) {
        return createParamProviders(injectQualifier,
            providerMethod.getParameterTypes(),
            providerMethod.getGenericParameterTypes(),
            providerMethod.getParameterAnnotations(),
            chain);
    }

    protected Provider<?>[] createParamProviders(final InjectQualifier<?> injectQualifier,
        final Constructor<?> providerMethod, Set<InjectQualifier<?>> chain) {
        return createParamProviders(injectQualifier,
            providerMethod.getParameterTypes(),
            providerMethod.getGenericParameterTypes(),
            providerMethod.getParameterAnnotations(),
            chain);
    }

    /**
     * Inject fields with @Inject annotation
     */
    public void injectFields(Object target) {
        List<InjectField> fields = null;
        if (this.injectFieldsCache.containsKey(target.getClass())) {
            fields = this.injectFieldsCache.get(target.getClass());
        } else {
            fields = Helper.getInjectFields(target.getClass());
            this.injectFieldsCache.putIfAbsent(target.getClass(), fields);
        }
        for (InjectField fieldProperties : fields) {
            Field injectField = fieldProperties.getField();
            Object value = fieldProperties.isProvider ? this.getProvider(fieldProperties.getInjectQualifier())
                : this.getInstance(fieldProperties.getInjectQualifier());
            try {
                injectField.set(target, value);
            } catch (IllegalArgumentException | IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
    }

}
