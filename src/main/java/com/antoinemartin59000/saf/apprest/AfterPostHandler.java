package com.antoinemartin59000.saf.apprest;

import com.antoinemartin59000.saf.entityservice.ISafEntityServiceProvider;
import com.antoinemartin59000.saf.entityservice.SafServiceSession;
import com.antoinemartin59000.saf.entityservice.serviceexception.SafServiceException;

@FunctionalInterface
public interface AfterPostHandler<P extends ISafEntityServiceProvider> {

    public void handle(P safEntityServiceProvider, SafServiceSession serviceSession, Long insertedId) throws SafServiceException;

}
