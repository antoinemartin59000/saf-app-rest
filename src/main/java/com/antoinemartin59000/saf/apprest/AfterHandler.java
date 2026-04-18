package com.antoinemartin59000.saf.apprest;

import java.util.function.Consumer;

public record AfterHandler(HttpMethod requestMethod, String resource, Consumer<Response> responseConsumer) {

}
