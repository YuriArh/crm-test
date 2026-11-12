package com.acme.dispatch.auth;

public enum Role {
    /** Creates delivery requests and sees only their own. */
    INITIATOR,
    /** Watches the exchange and handles problems for the requests of their service. */
    OPERATOR
}
