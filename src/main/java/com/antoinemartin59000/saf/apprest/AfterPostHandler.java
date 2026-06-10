package com.antoinemartin59000.saf.apprest;

import com.antoinemartin59000.saf.entityservice.SafServiceSession;
import com.antoinemartin59000.saf.entityservice.serviceexception.SafServiceException;

public abstract class AfterPostHandler {

    private final String resource;

    public AfterPostHandler(String resource) {
        this.resource = resource;
    }

    public String getResource() {
        return resource;
    }

    public abstract void handle(SafServiceSession serviceSession, Long insertedId) throws SafServiceException;

}
