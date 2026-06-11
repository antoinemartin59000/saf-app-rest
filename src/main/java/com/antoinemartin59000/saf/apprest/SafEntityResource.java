package com.antoinemartin59000.saf.apprest;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.IntFunction;

import javax.sql.DataSource;

import com.antoinemartin59000.saf.common.Pair;
import com.antoinemartin59000.saf.entity.ReflectionUtils;
import com.antoinemartin59000.saf.entity.SafEntity;
import com.antoinemartin59000.saf.entity.SafEntitySearch;
import com.antoinemartin59000.saf.entity.SafEntitySearch.EntitySearchBuilder;
import com.antoinemartin59000.saf.entityservice.ISafEntityServiceProvider;
import com.antoinemartin59000.saf.entityservice.SafEntityService;
import com.antoinemartin59000.saf.entityservice.SafServiceSession;
import com.antoinemartin59000.saf.entityservice.serviceexception.SafServiceException;
import com.fasterxml.jackson.core.JsonProcessingException;

import io.javalin.json.JavalinJackson;

public class SafEntityResource<P extends ISafEntityServiceProvider, E extends SafEntity, S extends SafEntitySearch<E>> {

    private final DataSource dataSource;
    private final JavalinJackson javalinJackson;

    private final Class<E> safEntityClass;
    private final Class<S> safEntitySearch;
    private final Map<String, BiConsumer<EntitySearchBuilder<S>, Pair<String, String>>> biConsumersByIntervalParameters;
    private final Map<String, BiConsumer<EntitySearchBuilder<S>, List<String>>> biConsumersByStandardParameters;
    private final SafEntityService<P, E, S> safEntityService;

    public SafEntityResource(Class<E> safEntityClass, Class<S> safEntitySearch, DataSource dataSource, P iSafEntityServiceProvider, JavalinJackson javalinJackson) {
        this.dataSource = dataSource;
        this.javalinJackson = javalinJackson;

        this.safEntityClass = safEntityClass;
        this.safEntitySearch = safEntitySearch;

        Class<EntitySearchBuilder<S>> searchBuilderClass = (Class<EntitySearchBuilder<S>>) safEntitySearch.getDeclaredClasses()[0];
        this.biConsumersByIntervalParameters = generateBiConsumersByIntervalParameters(searchBuilderClass);
        this.biConsumersByStandardParameters = generateBiConsumersByStandardParameters(searchBuilderClass);

        safEntityService = iSafEntityServiceProvider.get(safEntityClass);
    }

    public E get(String token, String resourceName, String dataSource, Long id) throws SafRestException {

        Map<String, List<String>> queryParams = new HashMap<>();
        queryParams.put("id", Arrays.asList(String.valueOf(id)));

        List<E> result = get(token, resourceName, queryParams, dataSource);

        if (result.isEmpty()) {
            throw new SafRestException(404, "entity not found with id " + id);
        }

        return result.get(0);
    }

    public Long post(String token, String resourceName, String json) throws SafRestException {

        try (SafServiceSession serviceSession = TokenHandler.generateServiceSession(dataSource, token)) {

            E entity = (E) javalinJackson.getMapper().readValue(json, safEntityClass);
            Long insertedId = safEntityService.insert(serviceSession, entity);

            return insertedId;
        } catch (SafServiceException e) {
            throw SafRestException.fromServiceException(e);
        } catch (IllegalArgumentException | SecurityException | JsonProcessingException e) {
            throw new SafRestException(500, "Server error.");
        }
    }

    public void patch(String token, String resourceName, long id, String json) throws SafRestException {

        try (SafServiceSession serviceSession = TokenHandler.generateServiceSession(dataSource, token)) {

            EntitySearchBuilder<S> searchBuilder = (EntitySearchBuilder<S>) safEntitySearch.getMethod("builder").invoke(null);
            biConsumersByStandardParameters.get("id").accept(searchBuilder, List.of(String.valueOf(id)));
            S search = searchBuilder.build();

            List<E> searchResult = serviceSession.asDeity(() -> safEntityService.searchFromDb(serviceSession, search));

            if (searchResult.isEmpty()) {
                throw new SafRestException(404, "entity not found with id " + id);
            }

            E entity = searchResult.get(0);

            Object entityFromBody;
            try {
                entityFromBody = javalinJackson.getMapper().readValue(json, safEntityClass);
            } catch (JsonProcessingException e) {
                throw new SafRestException(500, "Server error.");
            }

            copyPropertiesIfInJson(entity, entityFromBody, json);

            safEntityService.update(serviceSession, entity);

        } catch (SafServiceException e) {
            throw SafRestException.fromServiceException(e);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new SafRestException(500, "Server error.");
        }
    }

    public void delete(String token, String resourceName, long id) throws SafRestException {

        try (SafServiceSession serviceSession = TokenHandler.generateServiceSession(dataSource, token)) {

            try {
                safEntityService.delete(serviceSession, id);
            } catch (SafServiceException e) {
                throw SafRestException.fromServiceException(e);
            }

        } catch (SecurityException | IllegalArgumentException e) {
            throw new SafRestException(500, "Server error.");
        }
    }

    public List<E> get(String token, String resourceName, Map<String, List<String>> queryParams, String dataSourceQueryParam) throws SafRestException {
        try (SafServiceSession serviceSession = TokenHandler.generateServiceSession(dataSource, token)) {

            Class<?> searchClass = Class.forName(safEntityClass.getName() + "Search");
            EntitySearchBuilder<S> searchBuilder = (EntitySearchBuilder<S>) searchClass.getMethod("builder").invoke(null);

            for (Map.Entry<String, BiConsumer<EntitySearchBuilder<S>, Pair<String, String>>> entry : biConsumersByIntervalParameters.entrySet()) {
                String intervalParameterName = entry.getKey();
                BiConsumer<EntitySearchBuilder<S>, Pair<String, String>> biConsumer = entry.getValue();

                String minParamNam = "min-" + intervalParameterName;
                String maxParamNam = "max-" + intervalParameterName;

                if (queryParams.containsKey(minParamNam) || queryParams.containsKey(maxParamNam)) {
                    String minParam = null;
                    if (queryParams.containsKey(minParamNam)) {
                        minParam = queryParams.get(minParamNam).stream().findAny().orElse(null);
                    }

                    String maxParam = null;
                    if (queryParams.containsKey(maxParamNam)) {
                        maxParam = queryParams.get(maxParamNam).stream().findAny().orElse(null);
                    }

                    biConsumer.accept(searchBuilder, Pair.of(minParam, maxParam));
                }
            }

            for (Map.Entry<String, List<String>> entry : queryParams.entrySet()) {
                String paramName = entry.getKey();
                List<String> values = entry.getValue();

                if (biConsumersByIntervalParameters.containsKey(paramName.replaceFirst("min-", "").replaceFirst("max-", ""))) {
                    // already dealt in previous loop
                    continue;
                }

                if (!biConsumersByStandardParameters.containsKey(paramName)) {
                    throw new SafRestException(400, "invalid query parameter for resource " + resourceName + ": " + paramName);
                }

                BiConsumer<EntitySearchBuilder<S>, List<String>> biConsumer = biConsumersByStandardParameters.get(paramName);
                biConsumer.accept(searchBuilder, values);
            }

            S search = (S) searchBuilder.getClass().getMethod("build").invoke(searchBuilder);

            List<E> result;
            if ("database".equals(dataSourceQueryParam)) {
                result = safEntityService.searchFromDb(serviceSession, search);
            } else {
                result = safEntityService.searchFromCache(serviceSession, search);
            }

            return result;
        } catch (SafRestException e) {
            throw e;
        } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException | NoSuchMethodException | SecurityException | SafServiceException | ClassNotFoundException e) {
            throw new SafRestException(500, "Server error.");
        }
    }

    private Map<String, BiConsumer<EntitySearchBuilder<S>, Pair<String, String>>> generateBiConsumersByIntervalParameters(Class<EntitySearchBuilder<S>> searchBuilderClass) {

        List<Method> intervalByMethods = Arrays.stream(searchBuilderClass.getMethods())
                .filter(m -> m.getName().startsWith("by") && m.getName().endsWith("Interval"))
                .toList();

        Map<String, BiConsumer<EntitySearchBuilder<S>, Pair<String, String>>> biConsumersByIntervalListParameters = new HashMap<>();
        for (Method byMethod : intervalByMethods) {

            Type[] parameterTypes = byMethod.getGenericParameterTypes();

            if (parameterTypes.length != 1) {
                throw new RuntimeException("TODO");
            }

            ParameterizedType parameterizedTypeCollection = (ParameterizedType) ((GenericArrayType) parameterTypes[0]).getGenericComponentType();

            Type actualTypeArgument = parameterizedTypeCollection.getRawType();

            BiConsumer<EntitySearchBuilder<S>, Pair<String, String>> biConsumer;

            if (actualTypeArgument == Pair.class) {
                if (parameterizedTypeCollection.getActualTypeArguments()[0] == OffsetDateTime.class &&
                        parameterizedTypeCollection.getActualTypeArguments()[1] == OffsetDateTime.class) {
                    biConsumer = (b, stringPair) -> {
                        String first = stringPair.first();
                        String second = stringPair.second();

                        OffsetDateTime minOffsetDateTime = null;
                        OffsetDateTime maxOffsetDateTime = null;

                        if (first != null) {
                            minOffsetDateTime = OffsetDateTime.parse(first, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                        }

                        if (second != null) {
                            maxOffsetDateTime = OffsetDateTime.parse(second, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                        }

                        Pair<OffsetDateTime, OffsetDateTime> interval = makeInterval(minOffsetDateTime, maxOffsetDateTime);
                        try {
                            byMethod.invoke(b, Arrays.asList(interval));
                        } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
                            throw new RuntimeException("TODO", e);
                        }
                    };
                } else {
                    throw new RuntimeException("TODO - " + parameterizedTypeCollection);
                }

            } else {
                throw new RuntimeException("TODO - " + actualTypeArgument);
            }

            String byMethodName = byMethod.getName();
            String byMethodNameWithoutByPrefix = byMethodName.replaceFirst("by", "");
            String associatedQueryParmaeterKey = camelToKebabLowerCase(byMethodNameWithoutByPrefix).replace("-interval", "");
            biConsumersByIntervalListParameters.put(associatedQueryParmaeterKey, biConsumer);

        }

        return biConsumersByIntervalListParameters;

    }

    private Map<String, BiConsumer<EntitySearchBuilder<S>, List<String>>> generateBiConsumersByStandardParameters(Class<EntitySearchBuilder<S>> searchBuilderClass) {

        List<Method> standardByMethods = Arrays.stream(searchBuilderClass.getMethods())
                .filter(m -> m.getName().startsWith("by") && !m.getName().endsWith("Interval") && !m.getName().equals("byCustomWhereClause"))
                .toList();

        Map<String, BiConsumer<EntitySearchBuilder<S>, List<String>>> biConsumersByStandardListParameters = new HashMap<>();
        for (Method byMethod : standardByMethods) {

            Type[] parameterTypes = byMethod.getGenericParameterTypes();

            if (parameterTypes.length != 1) {
                throw new RuntimeException("TODO");
            }

            Class<?> actualTypeArgument;
            try {
                actualTypeArgument = ((Class<?>) parameterTypes[0]).componentType();
            } catch (Exception e) {
                throw new RuntimeException("TODO", e);
            }

            BiConsumer<EntitySearchBuilder<S>, List<String>> biConsumer;
            if (actualTypeArgument == Long.class) {
                biConsumer = (b, vals) -> invokeWithArray(b, byMethod, vals, Long::valueOf, Long[]::new);
            } else if (actualTypeArgument == String.class) {
                biConsumer = (b, vals) -> invokeWithArray(b, byMethod, vals, i -> i, String[]::new);
            } else if (actualTypeArgument == Integer.class) {
                biConsumer = (b, vals) -> invokeWithArray(b, byMethod, vals, Integer::valueOf, Integer[]::new);
            } else if (actualTypeArgument == Double.class) {
                biConsumer = (b, vals) -> invokeWithArray(b, byMethod, vals, Double::valueOf, Double[]::new);
            } else if (actualTypeArgument == BigDecimal.class) {
                biConsumer = (b, vals) -> invokeWithArray(b, byMethod, vals, BigDecimal::new, BigDecimal[]::new);
            } else if (actualTypeArgument == Boolean.class) {
                biConsumer = (b, vals) -> invokeWithArray(b, byMethod, vals, Boolean::valueOf, Boolean[]::new);
            } else if (actualTypeArgument.isEnum()) {
                Class<? extends Enum> enumClass = (Class<? extends Enum>) actualTypeArgument;
                biConsumer = (b, stringList) -> {
                    List<Enum<?>> castedValues = new ArrayList<>();
                    for (String stringValue : stringList) {
                        Enum<?> castedValue = Enum.valueOf(enumClass, stringValue);
                        castedValues.add(castedValue);
                    }

                    Class<?> paramType = byMethod.getParameterTypes()[0];
                    Class<?> componentType = paramType.getComponentType();

                    Object array = java.lang.reflect.Array.newInstance(componentType, castedValues.size());

                    for (int i = 0; i < castedValues.size(); i++) {
                        java.lang.reflect.Array.set(array, i, castedValues.get(i));
                    }

                    try {
                        byMethod.invoke(b, array);
                    } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
                        throw new RuntimeException("TODO", e);
                    }
                };
            } else {
                throw new RuntimeException("TODO - " + actualTypeArgument);
            }

            String byMethodName = byMethod.getName();
            String byMethodNameWithoutByPrefix = byMethodName.replaceFirst("by", "");
            String associatedQueryParmaeterKey = camelToKebabLowerCase(byMethodNameWithoutByPrefix);
            biConsumersByStandardListParameters.put(associatedQueryParmaeterKey, biConsumer);
        }

        return biConsumersByStandardListParameters;
    }

    private static <T> void invokeWithArray(Object builder, Method method, List<String> values,
            Function<String, T> parser, IntFunction<T[]> arrayCreator) {
        T[] array = values.stream()
                .map(parser)
                .toArray(arrayCreator);
        try {
            method.invoke(builder, (Object) array);
        } catch (Exception e) {
            throw new RuntimeException("TODO", e);
        }
    }

    public static String camelToKebabLowerCase(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }

        // Convert camelCase to kebab-case
        String kebab = input.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase();

        return kebab;
    }

    private static Pair<OffsetDateTime, OffsetDateTime> makeInterval(OffsetDateTime nullableMinDate, OffsetDateTime nullableMaxDate) {
        if (nullableMinDate != null && nullableMaxDate != null) {
            return Pair.of(nullableMinDate, nullableMaxDate);
        } else if (nullableMinDate != null) {
            return Pair.of(nullableMinDate, OffsetDateTime.MAX);
        } else if (nullableMaxDate != null) {
            return Pair.of(OffsetDateTime.MIN, nullableMaxDate);
        } else {
            return Pair.of(OffsetDateTime.MIN, OffsetDateTime.MAX);
        }
    }

    private static void copyPropertiesIfInJson(Object target, Object source, String json) {
        if (target == null || source == null) {
            throw new IllegalArgumentException("Neither target nor source can be null");
        }

        Class<?> sourceClass = source.getClass();
        Class<?> targetClass = target.getClass();

        List<Method> getterMethods = ReflectionUtils.collectGetterMethods(sourceClass);

        String lowerCaseJson = json.toLowerCase();

        for (Method getter : getterMethods) {

            String lowerCaseField = getter.getName().replaceFirst("get", "").toLowerCase();
            if (!lowerCaseJson.contains("\"" + lowerCaseField + "\"")) {
                continue;
            }

            try {
                String propertyName = getter.getName().startsWith("get")
                        ? getter.getName().substring(3)
                        : getter.getName().substring(2); // for isX methods

                // Try to find a matching setter in the target object
                Method setter = findSetter(targetClass, propertyName, getter.getReturnType());
                if (setter != null) {
                    Object value = getter.invoke(source);
                    setter.invoke(target, value);
                }
            } catch (Exception e) {
                throw new RuntimeException("TODO");
            }

        }
    }

    private static Method findSetter(Class<?> targetClass, String propertyName, Class<?> paramType) {
        String setterName = "set" + propertyName;
        try {
            return targetClass.getMethod(setterName, paramType);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

}
