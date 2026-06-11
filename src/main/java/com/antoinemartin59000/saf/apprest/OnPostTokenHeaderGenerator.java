package com.antoinemartin59000.saf.apprest;

import com.antoinemartin59000.saf.common.Pair;
import com.antoinemartin59000.saf.entityservice.ISafEntityServiceProvider;
import com.antoinemartin59000.saf.entityservice.SafServiceSession;
import com.antoinemartin59000.saf.entityservice.SafServiceSession.ServiceSessionInitiatorType;
import com.antoinemartin59000.saf.entityservice.serviceexception.SafServiceException;

public abstract class OnPostTokenHeaderGenerator {

    private final String resource;

    public OnPostTokenHeaderGenerator(String resource) {
        this.resource = resource;
    }

    public String getResource() {
        return resource;
    }

    public abstract Pair<ServiceSessionInitiatorType, Long> generateTokenDetail(ISafEntityServiceProvider iSafEntityServiceProvider, SafServiceSession serviceSession, Long insertedId) throws SafServiceException;

}
