package com.antoinemartin59000.saf.apprest;

import com.antoinemartin59000.saf.entityservice.ISafEntityServiceProvider;
import com.antoinemartin59000.saf.entityservice.SafServiceSession;
import com.antoinemartin59000.saf.entityservice.serviceexception.SafServiceException;

public abstract class OverridingPostHandler<P extends ISafEntityServiceProvider, I> {

    private final Class<I> inputClass;

    public OverridingPostHandler(Class<I> inputClass) {
        this.inputClass = inputClass;
    }

    public Class<I> getInputClass() {
        return inputClass;
    }

    public abstract long handle(P safEntityServiceProvider, SafServiceSession serviceSession, I input) throws SafServiceException;

}
