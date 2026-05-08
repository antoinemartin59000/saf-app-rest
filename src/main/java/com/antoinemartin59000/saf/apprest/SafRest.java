package com.antoinemartin59000.saf.apprest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import com.antoinemartin59000.saf.app.SafApp;
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
    private final List<RequestHandler<?>> handlers = new ArrayList<>();
    private final List<AfterHandler> afterHandlers = new ArrayList<>();

    public SafRest(int statusSocketPort, DataSource dataSource, ISafEntityServiceProvider iSafEntityServiceProvider) {
        super(statusSocketPort, 1000);
        this.dataSource = dataSource;
        this.iSafEntityServiceProvider = iSafEntityServiceProvider;
    }

    public void overrideHandler(RequestHandler<?> requestHandler) {
        handlers.add(requestHandler);
    }

    public void addAfterHandler(AfterHandler afterHandler) {
        afterHandlers.add(afterHandler);
    }

    @Override
    public void init() {

        JavalinJackson jsonMapper = new JavalinJackson();
        jsonMapper.getMapper().registerModule(new JavaTimeModule());
        jsonMapper.getMapper().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        EntityResource entityResource2 = new EntityResource(dataSource, iSafEntityServiceProvider, jsonMapper);

        Javalin app = Javalin.create(config -> {

            config.jsonMapper(jsonMapper);

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

            for (RequestHandler<?> requestHandler : handlers) {
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

                Response response = entityResource2.get(token, resource, dataSource, queryParameters);

                populateContext(response, ctx);
            });

            config.routes.get("/api/{resource}/{id}", ctx -> {

                String token = ctx.header("X-TOKEN");
                String dataSource = ctx.header("X-Data-Source");
                String resource = ctx.pathParamAsClass("resource", String.class).get();
                Long id = ctx.pathParamAsClass("id", Long.class).get();

                Response response = entityResource2.get(token, resource, dataSource, id);

                populateContext(response, ctx);
            });

            config.routes.post("/api/{resource}/", ctx -> {

                String token = ctx.header("X-TOKEN");
                String resource = ctx.pathParamAsClass("resource", String.class).get();
                String requestBody = ctx.body();

                Response response = entityResource2.post(token, resource, requestBody);

                populateContext(response, ctx);
            });

            config.routes.patch("/api/{resource}/{id}", ctx -> {

                String token = ctx.header("X-TOKEN");
                String resource = ctx.pathParamAsClass("resource", String.class).get();
                String requestBody = ctx.body();
                Long id = ctx.pathParamAsClass("id", Long.class).get();

                Response response = entityResource2.patch(token, resource, id, requestBody);

                populateContext(response, ctx);
            });

            config.routes.delete("/api/{resource}/{id}", ctx -> {

                String token = ctx.header("X-TOKEN");
                String resource = ctx.pathParamAsClass("resource", String.class).get();
                Long id = ctx.pathParamAsClass("id", Long.class).get();

                Response response = entityResource2.delete(token, resource, id);

                populateContext(response, ctx);
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
