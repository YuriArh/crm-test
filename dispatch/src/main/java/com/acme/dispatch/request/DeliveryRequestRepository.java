package com.acme.dispatch.request;

import java.util.UUID;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;

public interface DeliveryRequestRepository extends ListCrudRepository<DeliveryRequest, UUID> {

    /** Next human-readable request number; assigned by the application before insert. */
    @Query("SELECT nextval('delivery_request_number_seq')")
    long nextNumber();
}
