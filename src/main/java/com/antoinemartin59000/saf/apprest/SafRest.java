package com.antoinemartin59000.saf.apprest;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import com.antoinemartin59000.saf.app.SafApp;
import com.antoinemartin59000.saf.common.SubClassCollector;
import com.antoinemartin59000.saf.entity.Inflector;
import com.antoinemartin59000.saf.entity.SafEntity;
import com.antoinemartin59000.saf.entityservice.ISafEntityServiceProvider;
import com.antoinemartin59000.saf.entityservice.SafServiceSession;
import com.antoinemartin59000.saf.entityservice.SafServiceSession.ServiceSessionInitiatorType;
import com.antoinemartin59000.saf.entityservice.serviceexception.SafServiceException;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.javalin.Javalin;
import io.javalin.http.Handler;
import io.javalin.http.HandlerType;
import io.javalin.json.JavalinJackson;

public class SafRest extends SafApp {

    private final DataSource dataSource;
    private final ISafEntityServiceProvider safEntityServiceProvider;
    private final List<OverridingPostHandler<?>> overridingPostHandlers = new ArrayList<>();
    private final List<AfterPostHandler> afterPostHandlers = new ArrayList<>();

    public SafRest(int statusSocketPort, DataSource dataSource, ISafEntityServiceProvider safEntityServiceProvider) throws SQLException {
        super(statusSocketPort, 1000);
        this.dataSource = dataSource;
        this.safEntityServiceProvider = safEntityServiceProvider;
        this.addStatusFieldForDataSource(dataSource);
    }

    public void overridePostHandler(OverridingPostHandler<?> overridingPostHandler) {
        this.overridingPostHandlers.add(overridingPostHandler);
    }

    public void addAfterPostHandler(AfterPostHandler afterPostHandler) {
        afterPostHandlers.add(afterPostHandler);
    }

    @Override
    public void init() {

        JavalinJackson jsonMapper = new JavalinJackson();
        jsonMapper.getMapper().registerModule(new JavaTimeModule());
        jsonMapper.getMapper().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        Map<String, SafEntityResource> safEntityResourcesByResourceName = new HashMap<>();

        Set<Class<? extends SafEntity>> safEntityClasses = SubClassCollector.findAllSubclasses(SafEntity.class);
        for (Class<? extends SafEntity> safEntityClass : safEntityClasses) {

            Class<?> safEntitySearch;
            try {
                safEntitySearch = Class.forName(safEntityClass.getName() + "Search");
            } catch (ClassNotFoundException e) {
                throw new RuntimeException("Class " + safEntityClass.getName() + "Search does not exist.");
            }
            SafEntityResource entityResource = new SafEntityResource(safEntityClass, safEntitySearch, dataSource, safEntityServiceProvider, jsonMapper);

            String kebabSingular = SafEntityResource.camelToKebabLowerCase(safEntityClass.getSimpleName());
            String kebabPlural = Inflector.getInstance().pluralize(kebabSingular);

            System.out.println("registring resource " + kebabPlural);

            safEntityResourcesByResourceName.put(kebabPlural, entityResource);
        }

        Javalin app = Javalin.create(config -> {

            config.jsonMapper(jsonMapper);

            config.routes.exception(SafRestException.class, (e, ctx) -> {
                ResourceUtil.Error error = new ResourceUtil.Error();
                error.setMessage(e.getMessage());
                ctx.json(error);
                ctx.status(e.getCode());
            });

            config.routes.exception(Exception.class, (e, ctx) -> {
                e.printStackTrace();

                ctx.status(500);
            });

            config.bundledPlugins.enableCors(corsPluginConfigConsumer -> {
                corsPluginConfigConsumer.addRule(corsRuleConsumer -> {
                    corsRuleConsumer.anyHost();
                    corsRuleConsumer.exposeHeader("*");
                });
            });

            config.routes.get("/api", ctx -> ctx.result("Hello World!"));

            for (OverridingPostHandler<?> overridingHandler : overridingPostHandlers) {
                Handler javalinHandler = generateJavalinHandler(overridingHandler);

                String resource = "/api/" + overridingHandler.getResource();
                config.routes.post(resource, javalinHandler);
            }

            config.routes.get("/api/{resource}", ctx -> {

                String token = ctx.header("X-TOKEN");
                String dataSource = ctx.header("X-Data-Source");
                String resource = ctx.pathParamAsClass("resource", String.class).get();

                Map<String, List<String>> queryParameters = ctx.queryParamMap();

                Map<String, List<String>> interpretedQueryParameters = new HashMap<>();
                for (Map.Entry<String, List<String>> entity : queryParameters.entrySet()) {
                    String key = entity.getKey();
                    interpretedQueryParameters.putIfAbsent(key, new ArrayList<>());

                    for (String subQuery : entity.getValue()) {
                        if (entity.getKey().endsWith("-id") && !subQuery.isEmpty() && !Character.isDigit(subQuery.charAt(0))) {

                            if (!subQuery.contains("?")) {
                                throw new RuntimeException("server error");
                            }

                            String[] resourceAndParam2 = subQuery.split("\\?", 2); // TODO check if 2 is required
                            String resource2 = resourceAndParam2[0];
                            String[] params = resourceAndParam2[1].split("&");

                            Map<String, List<String>> multiValuedParams = new HashMap<>();
                            for (String param : params) {
                                String[] paramSplit = param.split("=", 2);
                                String name = paramSplit[0];
                                String value = paramSplit[1];

                                multiValuedParams.putIfAbsent(name, new ArrayList<>());
                                multiValuedParams.get(name).add(value);
                            }

                            SafEntityResource entityResource2 = getResource(safEntityResourcesByResourceName, resource2);
                            List<SafEntity> result2 = entityResource2.get(token, resource2, multiValuedParams, dataSource);

                            result2.stream()
                                    .map(SafEntity::getId)
                                    .map(String::valueOf)
                                    .forEach(idStr -> interpretedQueryParameters.get(key).add(idStr));
                        } else {
                            interpretedQueryParameters.get(entity.getKey()).add(subQuery);
                        }
                    }
                }

                SafEntityResource entityResource = getResource(safEntityResourcesByResourceName, resource);
                List result = entityResource.get(token, resource, interpretedQueryParameters, dataSource);

                ctx.status(200);
                ctx.json(result);
            });

            config.routes.get("/api/{resource}/{id}", ctx -> {

                String token = ctx.header("X-TOKEN");
                String dataSource = ctx.header("X-Data-Source");
                String resource = ctx.pathParamAsClass("resource", String.class).get();
                Long id = ctx.pathParamAsClass("id", Long.class).get();

                SafEntityResource entityResource = getResource(safEntityResourcesByResourceName, resource);
                Object result = entityResource.get(token, resource, dataSource, id);

                ctx.status(200);
                ctx.json(result);
            });

            config.routes.post("/api/{resource}/", ctx -> {

                String token = ctx.header("X-TOKEN");
                String resource = ctx.pathParamAsClass("resource", String.class).get();
                String requestBody = ctx.body();

                SafEntityResource entityResource = getResource(safEntityResourcesByResourceName, resource);
                Long insertedId = entityResource.post(token, resource, requestBody);

                // FIXME get absolute path
                // URI location = uriInfo.getAbsolutePathBuilder().path(String.valueOf(insertedId)).build();
                String location = resource + "/" + insertedId;

                ctx.status(201);
                ctx.header("location", location);
            });

            config.routes.patch("/api/{resource}/{id}", ctx -> {

                String token = ctx.header("X-TOKEN");
                String resource = ctx.pathParamAsClass("resource", String.class).get();
                String requestBody = ctx.body();
                Long id = ctx.pathParamAsClass("id", Long.class).get();

                SafEntityResource entityResource = getResource(safEntityResourcesByResourceName, resource);
                entityResource.patch(token, resource, id, requestBody);

                ctx.status(204);
            });

            config.routes.delete("/api/{resource}/{id}", ctx -> {

                String token = ctx.header("X-TOKEN");
                String resource = ctx.pathParamAsClass("resource", String.class).get();
                Long id = ctx.pathParamAsClass("id", Long.class).get();

                SafEntityResource entityResource = getResource(safEntityResourcesByResourceName, resource);
                entityResource.delete(token, resource, id);

                ctx.status(204);
            });

            for (AfterPostHandler afterPostHandler : afterPostHandlers) {

                config.routes.after("/api/" + afterPostHandler.getResource(), ctx -> {

                    if (!ctx.method().name().equals(HandlerType.POST.name())) {
                        return;
                    }

                    String token = ctx.header("X-TOKEN");

                    String[] array = ctx.res().getHeader("location").split("/");
                    Long insertedId = Long.valueOf(array[array.length - 1]);

                    try (SafServiceSession serviceSession = ResourceUtil.generateServiceSession(dataSource, token)) {
                        afterPostHandler.handle(safEntityServiceProvider, serviceSession, insertedId);
                    } catch (SafServiceException e) {
                        throw ResourceUtil.serviceExceptionToResponse(e);
                    }

                });

            }

        });

        app.start(8080);

    }

    private SafEntityResource getResource(Map<String, SafEntityResource> safEntityResourcesByResourceName, String resource) throws SafRestException {
        SafEntityResource entityResource = safEntityResourcesByResourceName.get(resource);

        if (entityResource == null) {
            throw new SafRestException(404, "resource " + resource + " does not exist");
        }

        return entityResource;
    }

    private <I, O> Handler generateJavalinHandler(OverridingPostHandler<I> overridingPostHandler) {
        return ctx -> {

            String token = ctx.header("X-TOKEN");

            I input = ctx.bodyAsClass(overridingPostHandler.getInputClass());
            long insertedId;
            try (SafServiceSession serviceSession = ResourceUtil.generateServiceSession(dataSource, token)) {
                insertedId = overridingPostHandler.handle(safEntityServiceProvider, serviceSession, input);
            } catch (SafServiceException e) {
                throw ResourceUtil.serviceExceptionToResponse(e);
            }

            String location = overridingPostHandler.getResource() + "/" + insertedId;

            ctx.status(201);
            ctx.header("location", location);
            if (overridingPostHandler.memberIdForToken(safEntityServiceProvider) != null) {
                long memberId;
                try (SafServiceSession serviceSession = new SafServiceSession(dataSource, ServiceSessionInitiatorType.PROCESS, null)) {
                    memberId = overridingPostHandler.memberIdForToken(safEntityServiceProvider).getMemberId(serviceSession, insertedId);
                } catch (SafServiceException e) {
                    throw ResourceUtil.serviceExceptionToResponse(e);
                }

                String newToken = ResourceUtil.generateToken(ServiceSessionInitiatorType.MEMBER, memberId); // FIXME
                ctx.header("X-TOKEN", newToken);
            } else if (overridingPostHandler.adminIdForToken(safEntityServiceProvider) != null) {
                long adminId;
                try (SafServiceSession serviceSession = new SafServiceSession(dataSource, ServiceSessionInitiatorType.PROCESS, null)) {
                    adminId = overridingPostHandler.adminIdForToken(safEntityServiceProvider).getMemberId(serviceSession, insertedId);
                } catch (SafServiceException e) {
                    throw ResourceUtil.serviceExceptionToResponse(e);
                }

                String newToken = ResourceUtil.generateToken(ServiceSessionInitiatorType.ADMINISTRATOR, adminId);
                ctx.header("X-TOKEN", newToken);
            }
        };
    }

    @Override
    public void onIteration() {
        // System.out.println("Rest running iteration");
    }

    @Override
    public void onShutDown() {
    }

}
