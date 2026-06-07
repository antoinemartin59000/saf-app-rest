package com.antoinemartin59000.saf.apprest;

import com.antoinemartin59000.saf.entityservice.SafServiceSession;
import com.antoinemartin59000.saf.entityservice.serviceexception.SafServiceException;

@FunctionalInterface
public interface SessionIdToMemberId {

    Long getMemberId(SafServiceSession serviceSession, Long insertedSessionId) throws SafServiceException;

}
