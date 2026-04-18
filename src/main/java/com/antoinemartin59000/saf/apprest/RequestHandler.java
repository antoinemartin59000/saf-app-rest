package com.antoinemartin59000.saf.apprest;

import java.util.function.Function;

public record RequestHandler<T>(HttpMethod httpMethod, String resource, Class<T> expectedBodyClass, Function<Request<T>, Response> f) {

    public static RequestHandler<Void> get(String resource, Function<Request<Void>, Response> f) {
        return new RequestHandler<Void>(HttpMethod.GET, resource, Void.class, f);
    }

    public static <T> RequestHandler<T> post(String resource, Class<T> expectedBodyClass, Function<Request<T>, Response> f) {
        return new RequestHandler<T>(HttpMethod.POST, resource, expectedBodyClass, f);
    }

}
