package com.antoinemartin59000.saf.apprest;

public class SafRestException extends Exception {

    private final int code;

    public SafRestException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }

}
