package com.antoinemartin59000.saf.apprest;

import java.util.List;
import java.util.Map;

import com.antoinemartin59000.saf.entityservice.SafServiceSession;
import com.antoinemartin59000.saf.entityservice.serviceexception.SafServiceException;

public abstract class OverridingGetOneHandler<O> {

    private final String resource;
    private final Class<O> outputClass;
    
    public OverridingGetOneHandler(String resource, Class<O> outputClass) {
        this.resource = resource;
        this.outputClass = outputClass;
    }

    public String getResource() {
        return resource;
    }

    public Class<O> getOutputClass() {
        return outputClass;
    }

    public abstract O handle(SafServiceSession serviceSession, Map<String, List<String>> queryParameters) throws SafServiceException;
    
}
