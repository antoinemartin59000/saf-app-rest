package com.antoinemartin59000.saf.apprest;

import com.antoinemartin59000.saf.entityservice.ISafEntityServiceProvider;
import com.antoinemartin59000.saf.entityservice.SafServiceSession;
import com.antoinemartin59000.saf.entityservice.serviceexception.SafServiceException;

public abstract class OverridingPostHandler<I> {

    private final String resource;
    private final Class<I> inputClass;

    public OverridingPostHandler(String resource, Class<I> inputClass) {
        this.resource = resource;
        this.inputClass = inputClass;
    }

    public String getResource() {
        return resource;
    }

    public Class<I> getInputClass() {
        return inputClass;
    }

    public abstract long handle(ISafEntityServiceProvider safEntityServiceProvider, SafServiceSession serviceSession, I input) throws SafServiceException;

    public SessionIdToMemberId adminIdForToken(ISafEntityServiceProvider safEntityServiceProvider) {
        return null;
    }

    public SessionIdToMemberId memberIdForToken(ISafEntityServiceProvider safEntityServiceProvider) {
        return null;
    }

}
