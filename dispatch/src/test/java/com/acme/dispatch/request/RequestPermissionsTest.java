package com.acme.dispatch.request;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.acme.dispatch.auth.CurrentUser;
import com.acme.dispatch.auth.Role;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RequestPermissionsTest {

    static final UUID NORTH = UUID.randomUUID();
    static final UUID SOUTH = UUID.randomUUID();

    static final CurrentUser OWNER = user(Role.INITIATOR, NORTH);
    static final CurrentUser OTHER_INITIATOR_SAME_SERVICE = user(Role.INITIATOR, NORTH);
    static final CurrentUser OPERATOR_SAME_SERVICE = user(Role.OPERATOR, NORTH);
    static final CurrentUser OPERATOR_OTHER_SERVICE = user(Role.OPERATOR, SOUTH);

    @Nested
    class View {

        @Test
        void ownerSeesOwnRequest() {
            assertThat(RequestPermissions.canView(OWNER, OWNER.id(), NORTH)).isTrue();
        }

        @Test
        void anotherInitiatorOfTheSameServiceDoesNot() {
            assertThat(RequestPermissions.canView(OTHER_INITIATOR_SAME_SERVICE, OWNER.id(), NORTH)).isFalse();
        }

        @Test
        void operatorSeesRequestsOfOwnService() {
            assertThat(RequestPermissions.canView(OPERATOR_SAME_SERVICE, OWNER.id(), NORTH)).isTrue();
        }

        @Test
        void operatorDoesNotSeeAnotherService() {
            assertThat(RequestPermissions.canView(OPERATOR_OTHER_SERVICE, OWNER.id(), NORTH)).isFalse();
        }
    }

    @Nested
    class Create {

        @Test
        void initiatorCreates() {
            assertThat(RequestPermissions.canCreate(OWNER)).isTrue();
        }

        @Test
        void operatorDoesNot() {
            assertThat(RequestPermissions.canCreate(OPERATOR_SAME_SERVICE)).isFalse();
        }
    }

    @Nested
    class Comment {

        @Test
        void ownerAndOperatorOfTheServiceComment() {
            assertThat(RequestPermissions.canComment(OWNER, OWNER.id(), NORTH)).isTrue();
            assertThat(RequestPermissions.canComment(OPERATOR_SAME_SERVICE, OWNER.id(), NORTH)).isTrue();
        }

        @Test
        void othersDoNot() {
            assertThat(RequestPermissions.canComment(OTHER_INITIATOR_SAME_SERVICE, OWNER.id(), NORTH)).isFalse();
            assertThat(RequestPermissions.canComment(OPERATOR_OTHER_SERVICE, OWNER.id(), NORTH)).isFalse();
        }
    }

    @Nested
    class Assign {

        @Test
        void operatorOfTheServiceAssigns() {
            assertThat(RequestPermissions.canAssign(OPERATOR_SAME_SERVICE, NORTH)).isTrue();
        }

        @Test
        void initiatorsAndOtherOperatorsDoNot() {
            assertThat(RequestPermissions.canAssign(OWNER, NORTH)).isFalse();
            assertThat(RequestPermissions.canAssign(OPERATOR_OTHER_SERVICE, NORTH)).isFalse();
        }
    }

    private static CurrentUser user(Role role, UUID serviceId) {
        return new CurrentUser(UUID.randomUUID(), role.name().toLowerCase(), "Test", null, role, serviceId, true);
    }
}
