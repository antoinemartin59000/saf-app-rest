package com.antoinemartin59000.saf.apprest;

import com.antoinemartin59000.saf.common.Pair;
import com.antoinemartin59000.saf.entityservice.ISafEntityServiceProvider;
import com.antoinemartin59000.saf.entityservice.SafServiceSession;
import com.antoinemartin59000.saf.entityservice.SafServiceSession.ServiceSessionInitiatorType;
import com.antoinemartin59000.saf.entityservice.serviceexception.SafServiceException;

public abstract class OnPostTokenHeaderGenerator<P extends ISafEntityServiceProvider> {

    public abstract Pair<ServiceSessionInitiatorType, Long> generateTokenDetail(P safEntityServiceProvider, SafServiceSession serviceSession, Long insertedId) throws SafServiceException;

}
