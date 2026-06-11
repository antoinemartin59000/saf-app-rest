package com.antoinemartin59000.saf.apprest;

import com.antoinemartin59000.saf.entityservice.ISafEntityServiceProvider;
import com.antoinemartin59000.saf.entityservice.SafServiceSession;
import com.antoinemartin59000.saf.entityservice.serviceexception.SafServiceException;

public abstract class AfterPostHandler<P extends ISafEntityServiceProvider> {

    private final String resource;

    public AfterPostHandler(String resource) {
        this.resource = resource;
    }

    public String getResource() {
        return resource;
    }

    public abstract void handle(P safEntityServiceProvider, SafServiceSession serviceSession, Long insertedId) throws SafServiceException;

}
