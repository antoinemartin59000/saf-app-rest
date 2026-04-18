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
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.IntFunction;

import javax.sql.DataSource;

import org.reflections.Reflections;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;

import com.antoinemartin59000.saf.common.Pair;
import com.antoinemartin59000.saf.entity.Inflector;
import com.antoinemartin59000.saf.entity.ReflectionUtils;
import com.antoinemartin59000.saf.entity.SafEntity;
import com.antoinemartin59000.saf.entity.SafEntitySearch;
import com.antoinemartin59000.saf.entityservice.ISafEntityServiceProvider;
import com.antoinemartin59000.saf.entityservice.SafEntityService;
import com.antoinemartin59000.saf.entityservice.SafServiceSession;
import com.antoinemartin59000.saf.entityservice.serviceexception.SafServiceException;
import com.fasterxml.jackson.core.JsonProcessingException;

import io.javalin.json.JavalinJackson;

public class EntityResource {

    private static final Map<String, Class<? extends SafEntity>> safEntityClassesByName = new HashMap<>();
    private static final Map<String /* entityName */, Map<String, BiConsumer<Object /* builder */, Pair<String, String>>>> biConsumersByIntervalParametersByEntity = new HashMap<>();
    private static final Map<String /* entityName */, Map<String, BiConsumer<Object /* builder */, List<String>>>> biConsumersByStandardParametersByEntity = new HashMap<>();

    static {
        try {

            Reflections reflections = new Reflections(new ConfigurationBuilder()
                    .setUrls(ClasspathHelper.forPackage(""))
                    .setExpandSuperTypes(false) // faster, we only care about direct subclasses
            );

            Set<Class<? extends SafEntity>> safEntityClasses = reflections.getSubTypesOf(SafEntity.class);

            for (Class<? extends SafEntity> safEntityClass : safEntityClasses) {
                try {
                    String entityName = safEntityClass.getSimpleName();

                    safEntityClassesByName.put(entityName, safEntityClass);
                    Class<?> searchClass = Class.forName(safEntityClass.getName() + "Search");
                    Class<?> searchBuilderClass = searchClass.getDeclaredClasses()[0];

                    Map<String, BiConsumer<Object /* builder */, Pair<String, String>>> biConsumersByIntervalListParameters = generateBiConsumersByIntervalParameters(searchBuilderClass);
                    biConsumersByIntervalParametersByEntity.put(entityName, biConsumersByIntervalListParameters);

                    Map<String, BiConsumer<Object /* builder */, List<String>>> biConsumersByStandardListParameters = generateBiConsumersByStandardParameters(searchBuilderClass);
                    biConsumersByStandardParametersByEntity.put(entityName, biConsumersByStandardListParameters);

                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private final DataSource dataSource;
    private final ISafEntityServiceProvider iSafEntityServiceProvider;
    private final JavalinJackson javalinJackson;

    public EntityResource(DataSource dataSource, ISafEntityServiceProvider iSafEntityServiceProvider, JavalinJackson javalinJackson) {
        this.dataSource = dataSource;
        this.iSafEntityServiceProvider = iSafEntityServiceProvider;
        this.javalinJackson = javalinJackson;
    }

    private static class ResourceNotFoundException extends Exception {

    }

    private SafEntityService<? extends SafEntity, ? extends SafEntitySearch> getService(String resourceName) throws ResourceNotFoundException {
        String entityName = toCamelCaseSingular(resourceName);
        try {
            Method getter = iSafEntityServiceProvider.getClass().getMethod("get" + entityName + "Service");
            return (SafEntityService<? extends SafEntity, ? extends SafEntitySearch>) getter.invoke(iSafEntityServiceProvider);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new ResourceNotFoundException();
        }
    }

    public Response get(String token, String resourceName, String dataSource, Long id) {

        Map<String, List<String>> queryParams = new HashMap<>();
        queryParams.put("id", Arrays.asList(String.valueOf(id)));

        Response response = get(token, resourceName, queryParams, dataSource);

        if (response.status() != 200) {
            return response;
        }

        List<?> result = (List<?>) response.body();

        if (result.isEmpty()) {
            ResourceUtil.Error error = new ResourceUtil.Error();
            error.setMessage("entity not found with id " + id);
            return new Response(404, error, null);
        }

        return new Response(200, result.get(0), null);
    }

    public Response get(String token, String resourceName, String dataSource, Map<String, List<String>> queryParameters) {

        try {

            Map<String, List<String>> interpretedQueryParameters = new HashMap<>();
            for (Map.Entry<String, List<String>> entity : queryParameters.entrySet()) {
                String key = entity.getKey();
                interpretedQueryParameters.putIfAbsent(key, new ArrayList<>());

                for (String value : entity.getValue()) {
                    if (entity.getKey().endsWith("-id") && !value.isEmpty() && !Character.isDigit(value.charAt(0))) {
                        Response response = resolveIdsFromSubQuery(token, value, dataSource);

                        if (response.status() != 200) {
                            return response;
                        }

                        List<SafEntity> entities = (List<SafEntity>) response.body();
                        entities.stream()
                                .map(SafEntity::getId)
                                .map(String::valueOf)
                                .forEach(idStr -> interpretedQueryParameters.get(key).add(idStr));
                    } else {
                        interpretedQueryParameters.get(entity.getKey()).add(value);
                    }
                }
            }

            return get(token, resourceName, interpretedQueryParameters, dataSource);
        } catch (Exception e) {
            return ResourceUtil.logServerErrorAndMakeResponse(resourceName, queryParameters, e);
        }
    }

    private Response resolveIdsFromSubQuery(String token, String subQuery, String datasource) {

        if (!subQuery.contains("?")) {
            throw new RuntimeException("server error");
        }

        String[] resourceAndParam = subQuery.split("\\?", 2); // TODO check if 2 is required
        String resource = resourceAndParam[0];
        String[] params = resourceAndParam[1].split("&");

        Map<String, List<String>> multiValuedParams = new HashMap<>();
        for (String param : params) {
            String[] paramSplit = param.split("=", 2);
            String name = paramSplit[0];
            String value = paramSplit[1];

            multiValuedParams.putIfAbsent(name, new ArrayList<>());
            multiValuedParams.get(name).add(value);
        }

        return get(token, resource, multiValuedParams, datasource);
    }

    public Response post(String token, String resourceName, String json) {

        try (SafServiceSession serviceSession = ResourceUtil.generateServiceSession(dataSource, token)) {
            String entityName = toCamelCaseSingular(resourceName);
            SafEntityService service = getService(resourceName);

            Class<? extends SafEntity> safEntityClass = safEntityClassesByName.get(entityName);

            SafEntity entity = (SafEntity) javalinJackson.getMapper().readValue(json, safEntityClass);
            Long insertedId = service.insert(serviceSession, entity);

            // FIXME get absolute path
            // URI location = uriInfo.getAbsolutePathBuilder().path(String.valueOf(insertedId)).build();
            String location = resourceName + "/" + insertedId;

            return new Response(201, null, Map.of("location", location));
        } catch (ResourceNotFoundException e) {
            ResourceUtil.Error error = new ResourceUtil.Error();
            error.setMessage("No resource named " + resourceName);
            return new Response(404, error, null);
        } catch (SafServiceException e) {
            return ResourceUtil.serviceExceptionToResponse(e);
        } catch (InvalidTokenException e1) {
            ResourceUtil.Error error = new ResourceUtil.Error();
            error.setMessage("Invalid Token.");
            return new Response(401, error, null);
        } catch (IllegalArgumentException | SecurityException | JsonProcessingException e) {
            return ResourceUtil.logServerErrorAndMakeResponse("POST " + resourceName, Map.of(), e);
        }
    }

    public Response patch(String token, String resourceName, long id, String json) {

        String entityName = toCamelCaseSingular(resourceName);

        try (SafServiceSession serviceSession = ResourceUtil.generateServiceSession(dataSource, token)) {

            SafEntityService service = getService(resourceName);

            Class<? extends SafEntity> safEntityClass = safEntityClassesByName.get(entityName);
            Class<?> searchClass = Class.forName(safEntityClass.getName() + "Search");
            Object searchBuilder = searchClass.getMethod("builder").invoke(null);
            biConsumersByStandardParametersByEntity.get(entityName).get("id").accept(searchBuilder, List.of(String.valueOf(id)));
            SafEntitySearch search = (SafEntitySearch) searchBuilder.getClass().getMethod("build").invoke(searchBuilder);

            // FIXME? a bit hacky, we have to open a transaction to indicate we are the code and not the player
            serviceSession.openTransaction(false);

            List<?> searchResult;
            try {
                searchResult = service.searchFromDb(serviceSession, search);
            } finally {
                serviceSession.closeTransaction();
            }

            if (searchResult.isEmpty()) {
                return new Response(404, "entity not found with id " + id, null);
            }

            Object entity = searchResult.get(0);

            //

            Object entityFromBody;
            try {
                entityFromBody = javalinJackson.getMapper().readValue(json, safEntityClass);
            } catch (JsonProcessingException e) {
                return ResourceUtil.logServerErrorAndMakeResponse("PATCH " + resourceName + "/" + id, Map.of(), e);
            }

            copyPropertiesIfInJson(entity, entityFromBody, json);

            service.update(serviceSession, (SafEntity) entity);

            return new Response(204, null, null);
        } catch (ResourceNotFoundException e) {
            ResourceUtil.Error error = new ResourceUtil.Error();
            error.setMessage("No resource named " + resourceName);
            return new Response(404, error, null);
        } catch (SafServiceException e) {
            return ResourceUtil.serviceExceptionToResponse(e);
        } catch (InvalidTokenException e) {
            ResourceUtil.Error error = new ResourceUtil.Error();
            error.setMessage("Invalid Token");
            return new Response(401, error, null);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            return ResourceUtil.logServerErrorAndMakeResponse("PATCH " + resourceName + "/" + id, Map.of(), e);
        }
    }

    public Response delete(String token, String resourceName, long id) {

        try (SafServiceSession serviceSession = ResourceUtil.generateServiceSession(dataSource, token)) {
            SafEntityService service = getService(resourceName);

            try {
                service.delete(serviceSession, id);
            } catch (SafServiceException e) {
                return ResourceUtil.serviceExceptionToResponse(e);
            }

            return new Response(204, null, null);
        } catch (ResourceNotFoundException e) {
            ResourceUtil.Error error = new ResourceUtil.Error();
            error.setMessage("No resource named " + resourceName);
            return new Response(404, error, null);
        } catch (InvalidTokenException e1) {
            ResourceUtil.Error error = new ResourceUtil.Error();
            error.setMessage("Invalid Token");
            return new Response(401, error, null);
        } catch (SecurityException | IllegalArgumentException e) {
            return ResourceUtil.logServerErrorAndMakeResponse("DELETE" + resourceName + "/" + id, Map.of(), e);
        }
    }

    public Response get(String token, String resourceName, Map<String, List<String>> queryParams, String dataSourceQueryParam) {
        try (SafServiceSession serviceSession = ResourceUtil.generateServiceSession(dataSource, token)) {

            // Convert path param to class prefix (e.g., "conventional-entity" → "ConventionalEntity")
            String entityName = toCamelCaseSingular(resourceName);

            Map<String, BiConsumer<Object /* builder */, Pair<String, String>>> biConsumersByIntervalListParameters = biConsumersByIntervalParametersByEntity.get(entityName);

            Map<String, BiConsumer<Object /* builder */, List<String>>> biConsumersByStandardListParameters = biConsumersByStandardParametersByEntity.get(entityName);

            if (biConsumersByStandardListParameters == null) {
                System.out.println();
            }

            Class<? extends SafEntity> safEntityClass = safEntityClassesByName.get(entityName);
            Class<?> searchClass = Class.forName(safEntityClass.getName() + "Search");
            Object searchBuilder = searchClass.getMethod("builder").invoke(null);

            for (Map.Entry<String, BiConsumer<Object /* builder */, Pair<String, String>>> entry : biConsumersByIntervalListParameters.entrySet()) {
                String intervalParameterName = entry.getKey();
                BiConsumer<Object /* builder */, Pair<String, String>> biConsumer = entry.getValue();

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

                if (biConsumersByIntervalListParameters.containsKey(paramName.replaceFirst("min-", "").replaceFirst("max-", ""))) {
                    // already dealt in previous loop
                    continue;
                }

                if (!biConsumersByStandardListParameters.containsKey(paramName)) {
                    ResourceUtil.Error error = new ResourceUtil.Error();
                    error.setMessage("invalid query parameter for resource " + resourceName + ": " + paramName);
                    return new Response(400, error, null);
                }

                BiConsumer<Object, List<String>> biConsumer = biConsumersByStandardListParameters.get(paramName);
                biConsumer.accept(searchBuilder, values);
            }

            SafEntitySearch search = (SafEntitySearch) searchBuilder.getClass().getMethod("build").invoke(searchBuilder);
            SafEntityService service = getService(resourceName);

            List result;
            if ("database".equals(dataSourceQueryParam)) {
                result = service.searchFromDb(serviceSession, search);
            } else {
                result = service.searchFromCache(serviceSession, search);
            }

            return new Response(200, result, null);
        } catch (InvalidTokenException e1) {
            ResourceUtil.Error error = new ResourceUtil.Error();
            error.setMessage("Invalid Token.");
            return new Response(401, error, null);
        } catch (Exception e) {
            return ResourceUtil.logServerErrorAndMakeResponse(resourceName, queryParams, e);
        }
    }

    private static Map<String, BiConsumer<Object /* builder */, Pair<String, String>>> generateBiConsumersByIntervalParameters(Class<?> searchBuilderClass) {

        List<Method> intervalByMethods = Arrays.stream(searchBuilderClass.getMethods())
                .filter(m -> m.getName().startsWith("by") && m.getName().endsWith("Interval"))
                .toList();

        Map<String, BiConsumer<Object /* builder */, Pair<String, String>>> biConsumersByIntervalListParameters = new HashMap<>();
        for (Method byMethod : intervalByMethods) {

            Type[] parameterTypes = byMethod.getGenericParameterTypes();

            if (parameterTypes.length != 1) {
                throw new RuntimeException("TODO");
            }

            ParameterizedType parameterizedTypeCollection = (ParameterizedType) ((GenericArrayType) parameterTypes[0]).getGenericComponentType();

            Type actualTypeArgument = parameterizedTypeCollection.getRawType();

            BiConsumer<Object, Pair<String, String>> biConsumer;

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

    private static Map<String, BiConsumer<Object /* builder */, List<String>>> generateBiConsumersByStandardParameters(Class<?> searchBuilderClass) {

        List<Method> standardByMethods = Arrays.stream(searchBuilderClass.getMethods())
                .filter(m -> m.getName().startsWith("by") && !m.getName().endsWith("Interval") && !m.getName().equals("byCustomWhereClause"))
                .toList();

        Map<String, BiConsumer<Object /* builder */, List<String>>> biConsumersByStandardListParameters = new HashMap<>();
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

            BiConsumer<Object, List<String>> biConsumer;
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

    private static String toCamelCaseSingular(String kebabCase) {
        String[] parts = kebabCase.split("-");
        StringBuilder sb = new StringBuilder();

        for (String part : parts) {
            String singular = Inflector.getInstance().singularize(part);
            sb.append(Character.toUpperCase(singular.charAt(0)))
                    .append(singular.substring(1));
        }

        return sb.toString();
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

    public static void copyPropertiesIfInJson(Object target, Object source, String json) {
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
                e.printStackTrace(); // FIXME
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
