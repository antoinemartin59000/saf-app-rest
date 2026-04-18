package com.antoinemartin59000.saf.apprest;

import java.util.Map;

public record Request<T>(String token, T bodyRequest, Map<String, String> pathParams) {

}
