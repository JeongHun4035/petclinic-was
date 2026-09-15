package org.springframework.samples.petclinic.repository;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.samples.petclinic.model.Appointment;

public interface AppointmentRepository {
    Appointment findById(int id, boolean lock);
    List<Appointment> findAll(Integer petId, Integer vetId, Integer ownerId, String status,
                              OffsetDateTime from, OffsetDateTime to, int limit, int offset);
    void lockPetAndVet(int petId, int vetId);
    boolean overlaps(Integer excludedId, int petId, int vetId, OffsetDateTime start, OffsetDateTime end);
    int insert(Appointment appointment);
    void update(Appointment appointment);
    void confirm(int id);
    void cancel(int id);
}
