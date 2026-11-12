package com.acme.dispatch.request;

import java.util.UUID;

/** A user shown on a request (owner, assignee). */
public record PersonRef(UUID id, String displayName) {
}
