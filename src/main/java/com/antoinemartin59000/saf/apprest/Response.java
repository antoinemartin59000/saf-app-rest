package com.antoinemartin59000.saf.apprest;

import java.util.Map;

public record Response(int status, Object body, Map<String, String> headers) {

}
