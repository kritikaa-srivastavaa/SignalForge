package com.kritika.signalforge.auth;

public enum UserRole {
    VIEWER, OPERATOR, ADMIN;

    @com.fasterxml.jackson.annotation.JsonCreator
    public static UserRole fromJson(String value) {
        return value == null ? null : valueOf(value);
    }
}