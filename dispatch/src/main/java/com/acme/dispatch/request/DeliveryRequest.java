package com.acme.dispatch.request;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Version;

/**
 * Delivery request aggregate. Content is immutable after creation; users change only the comment
 * and the assignee. The business result is written by the result worker with conditional SQL,
 * which also bumps {@code version}, so a user save based on an old version fails instead of
 * overwriting the result.
 */
public class DeliveryRequest {

    @Id
    private UUID id;
    private Long number;
    private UUID serviceId;
    private UUID ownerId;
    private UUID assigneeId;

    private String cargoDescription;
    private BigDecimal weightKg;
    private String deliveryAddress;
    private LocalDate deadline;

    private String comment;

    private BusinessStatus businessStatus;
    private String trackingNumber;
    private BigDecimal price;
    private String rejectionReason;
    private Instant resultAt;

    private Instant createdAt;
    private Instant updatedAt;

    /** {@code null} marks a new, not yet inserted aggregate. */
    @Version
    private Long version;

    @PersistenceCreator
    DeliveryRequest() {
    }

    public static DeliveryRequest create(UUID id, long number, UUID serviceId, UUID ownerId,
                                         DeliveryContent content, Instant now) {
        DeliveryRequest request = new DeliveryRequest();
        request.id = Objects.requireNonNull(id);
        request.number = number;
        request.serviceId = Objects.requireNonNull(serviceId);
        request.ownerId = Objects.requireNonNull(ownerId);
        request.cargoDescription = content.cargoDescription();
        request.weightKg = content.weightKg();
        request.deliveryAddress = content.deliveryAddress();
        request.deadline = content.deadline();
        request.businessStatus = BusinessStatus.AWAITING;
        request.createdAt = now;
        request.updatedAt = now;
        return request;
    }

    /** @return {@code true} if the comment actually changed */
    public boolean changeComment(String newComment, Instant now) {
        if (Objects.equals(comment, newComment)) {
            return false;
        }
        comment = newComment;
        updatedAt = now;
        return true;
    }

    /** @return {@code true} if the assignee actually changed */
    public boolean changeAssignee(UUID newAssigneeId, Instant now) {
        if (Objects.equals(assigneeId, newAssigneeId)) {
            return false;
        }
        assigneeId = newAssigneeId;
        updatedAt = now;
        return true;
    }

    public DeliveryContent content() {
        return new DeliveryContent(cargoDescription, weightKg, deliveryAddress, deadline);
    }

    public UUID getId() {
        return id;
    }

    public Long getNumber() {
        return number;
    }

    public UUID getServiceId() {
        return serviceId;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public UUID getAssigneeId() {
        return assigneeId;
    }

    public String getCargoDescription() {
        return cargoDescription;
    }

    public BigDecimal getWeightKg() {
        return weightKg;
    }

    public String getDeliveryAddress() {
        return deliveryAddress;
    }

    public LocalDate getDeadline() {
        return deadline;
    }

    public String getComment() {
        return comment;
    }

    public BusinessStatus getBusinessStatus() {
        return businessStatus;
    }

    public String getTrackingNumber() {
        return trackingNumber;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public Instant getResultAt() {
        return resultAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }
}
