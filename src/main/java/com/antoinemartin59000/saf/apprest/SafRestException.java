package com.antoinemartin59000.saf.apprest;

import com.antoinemartin59000.saf.entityservice.serviceexception.SafServiceException;

public class SafRestException extends Exception {

    public static SafRestException fromServiceException(SafServiceException serviceException) {
        int httpStatus;

        switch (serviceException.getResultStatus()) {
            case OK:
                httpStatus = 200;
                break;
            case ERROR:
                httpStatus = 400;
                break;
            case UNAUTHORIZED:
                httpStatus = 401;
                break;
            case NOT_FOUND:
                httpStatus = 404;
                break;
            case DEPENDENCY_CONFLICT, DUPLICATION_CONFLICT:
                httpStatus = 409;
                break;
            case INTERNAL_ERROR:
                httpStatus = 500;
                break;
            case FORBIDDEN:
                httpStatus = 403;
                break;
            default:
                httpStatus = 500;
        }

        return new SafRestException(httpStatus, serviceException.getErrorMessage());
    }

    private final int code;

    public SafRestException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }

}
