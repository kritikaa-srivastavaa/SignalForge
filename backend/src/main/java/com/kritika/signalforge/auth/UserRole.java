package com.kritika.signalforge.auth;

public enum UserRole {
    NO_ACCESS, VIEWER, OPERATOR, ADMIN;

    @com.fasterxml.jackson.annotation.JsonCreator
    public static UserRole fromJson(String value) {
        return value == null ? null : valueOf(value);
    }
}