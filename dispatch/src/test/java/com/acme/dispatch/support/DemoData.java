package com.acme.dispatch.support;

import java.util.UUID;

/** Ids and credentials of the demo reference data from {@code V5__demo_users.sql}. */
public final class DemoData {

    public static final UUID NORTH = UUID.fromString("0195f000-0000-7000-8000-000000000001");
    public static final UUID SOUTH = UUID.fromString("0195f000-0000-7000-8000-000000000002");

    public static final UUID NORTH_INITIATOR = UUID.fromString("0195f000-0000-7000-8000-000000000101");
    public static final UUID NORTH_OPERATOR = UUID.fromString("0195f000-0000-7000-8000-000000000102");
    public static final UUID SOUTH_INITIATOR = UUID.fromString("0195f000-0000-7000-8000-000000000201");

    public static final String NORTH_INITIATOR_LOGIN = "north.initiator";
    public static final String NORTH_OPERATOR_LOGIN = "north.operator";
    public static final String SOUTH_INITIATOR_LOGIN = "south.initiator";

    public static final String PASSWORD = "demo1234";

    private DemoData() {
    }
}
