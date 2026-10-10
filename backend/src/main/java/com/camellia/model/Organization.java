package com.camellia.model;

public record Organization(String orgId, String name, String orgType, String sourceSystem, String sourceFormat,
                           String city, String state) {}
