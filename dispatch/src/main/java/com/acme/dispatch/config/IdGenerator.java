package com.acme.dispatch.config;

import java.util.UUID;

/** Source of all entity ids and operation ids. */
public interface IdGenerator {

    UUID newId();
}
