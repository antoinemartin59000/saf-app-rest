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
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.json.JavalinJackson;

public class SafRest extends SafApp {

    private final DataSource dataSource;
    private final ISafEntityServiceProvider iSafEntityServiceProvider;
    private final List<RequestHandler<?>> overridenHandlers = new ArrayList<>();
    private final List<AfterHandler> afterHandlers = new ArrayList<>();

    public SafRest(int statusSocketPort, DataSource dataSource, ISafEntityServiceProvider iSafEntityServiceProvider) throws SQLException {
        super(statusSocketPort, 1000);
        this.dataSource = dataSource;
        this.iSafEntityServiceProvider = iSafEntityServiceProvider;
        this.addStatusFieldForDataSource(dataSource);
    }

    public void overrideHandler(RequestHandler<?> overridenHandlers) {
        this.overridenHandlers.add(overridenHandlers);
    }

    public void addAfterHandler(AfterHandler afterHandler) {
        afterHandlers.add(afterHandler);
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
            SafEntityResource entityResource = new SafEntityResource(safEntityClass, safEntitySearch, dataSource, iSafEntityServiceProvider, jsonMapper);

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
                Response response = new Response(e.getCode(), error, null);

                populateContext(response, ctx);
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

            for (RequestHandler<?> requestHandler : overridenHandlers) {
                HttpMethod httpMethod = requestHandler.httpMethod();
                String resource = requestHandler.resource();

                Handler javalinHandler = generateJavalinHandler(requestHandler);

                String uri = "/api/" + resource;

                if (httpMethod == HttpMethod.DELETE) {
                    config.routes.delete(uri, javalinHandler);
                } else if (httpMethod == HttpMethod.GET) {
                    config.routes.get(uri, javalinHandler);
                } else if (httpMethod == HttpMethod.PATCH) {
                    config.routes.patch(uri, javalinHandler);
                } else if (httpMethod == HttpMethod.POST) {
                    config.routes.post(uri, javalinHandler);
                } else if (httpMethod == HttpMethod.PUT) {
                    config.routes.put(uri, javalinHandler);
                }

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

            for (AfterHandler afterHandler : afterHandlers) {

                config.routes.after("/api/" + afterHandler.resource(), ctx -> {

                    if (!ctx.method().name().equals(afterHandler.requestMethod().name())) {
                        return;
                    }

                    Map<String, String> responseHeaders = new HashMap<>();
                    for (String headerName : ctx.res().getHeaderNames()) {
                        responseHeaders.put(headerName, ctx.res().getHeader(headerName));
                    }

                    Response response = new Response(ctx.res().getStatus(), null, responseHeaders);
                    afterHandler.responseConsumer().accept(response);

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

    private <T> Handler generateJavalinHandler(RequestHandler<T> requestHandler) {
        return ctx -> {

            T bodyReques = null;

            if (requestHandler.expectedBodyClass() != Void.class) {
                bodyReques = ctx.bodyAsClass(requestHandler.expectedBodyClass());
            }

            String token = ctx.header("X-TOKEN");

            Request<T> request = new Request<T>(token, bodyReques, ctx.pathParamMap());
            Response response = requestHandler.f().apply(request);
            populateContext(response, ctx);
        };
    }

    private void populateContext(Response response, Context ctx) {

        if (response.body() != null) {
            ctx.json(response.body());
        }
        ctx.status(response.status());

        if (response.headers() != null) {
            for (Map.Entry<String, String> entry : response.headers().entrySet()) {
                ctx.header(entry.getKey(), entry.getValue());
            }
        }

    }

    @Override
    public void onIteration() {
        // System.out.println("Rest running iteration");
    }

    @Override
    public void onShutDown() {
    }

}
