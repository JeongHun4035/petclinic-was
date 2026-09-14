package org.springframework.samples.petclinic.model;

import java.time.OffsetDateTime;

/** A scheduled consultation; completed clinical records remain in visits. */
public record Appointment(Integer id, Integer petId, Integer vetId, Integer ownerId,
                          OffsetDateTime startTime, OffsetDateTime endTime,
                          String reason, String status) {
}
