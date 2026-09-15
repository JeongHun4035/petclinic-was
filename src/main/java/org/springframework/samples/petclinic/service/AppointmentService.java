package org.springframework.samples.petclinic.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.samples.petclinic.model.Appointment;
import org.springframework.samples.petclinic.repository.AppointmentRepository;
import org.springframework.samples.petclinic.security.AccessPolicy;
import org.springframework.samples.petclinic.rest.dto.AppointmentDto;
import org.springframework.samples.petclinic.rest.dto.AppointmentFieldsDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class AppointmentService {
    private final AppointmentRepository repository;
    private final AccessPolicy access;

    public AppointmentService(AppointmentRepository repository, AccessPolicy access) {
        this.repository = repository;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public AppointmentDto get(int id) {
        Appointment appointment = required(id, false);
        access.requireAppointmentRead(appointment);
        return toDto(appointment);
    }

    @Transactional(readOnly = true)
    public List<AppointmentDto> list(Integer petId, Integer vetId, Integer ownerId, String status,
                                     OffsetDateTime from, OffsetDateTime to, Integer limit, Integer offset) {
        int pageLimit = limit == null ? 20 : limit;
        int pageOffset = offset == null ? 0 : offset;
        if (pageLimit < 1 || pageLimit > 100 || pageOffset < 0
                || (petId != null && petId < 1) || (vetId != null && vetId < 1) || (ownerId != null && ownerId < 1)) {
            throw error(HttpStatus.BAD_REQUEST, "IDs must be positive; limit must be 1-100 and offset must be non-negative");
        }
        if (status != null && !List.of("PENDING", "CONFIRMED", "CANCELLED").contains(status)) {
            throw error(HttpStatus.BAD_REQUEST, "Status must be PENDING, CONFIRMED or CANCELLED");
        }
        if (from != null && to != null && !from.isBefore(to)) {
            throw error(HttpStatus.BAD_REQUEST, "from must be earlier than to");
        }
        if (!access.clinicalStaff()) {
            var account = access.currentAccount();
            if (access.hasRole("ROLE_OWNER") && account.ownerId() != null) {
                if (ownerId != null && !ownerId.equals(account.ownerId())) {
                    throw error(HttpStatus.FORBIDDEN, "Cannot list another owner's appointments");
                }
                ownerId = account.ownerId();
            } else if (access.hasRole("ROLE_VET") && account.vetId() != null) {
                if (vetId != null && !vetId.equals(account.vetId())) {
                    throw error(HttpStatus.FORBIDDEN, "Cannot list another vet's appointments");
                }
                vetId = account.vetId();
            } else {
                throw error(HttpStatus.FORBIDDEN, "Account has no linked clinical profile");
            }
        }
        return repository.findAll(petId, vetId, ownerId, status, from, to, pageLimit, pageOffset)
            .stream().map(this::toDto).toList();
    }

    public AppointmentDto create(AppointmentFieldsDto request) {
        Appointment appointment = prepare(null, request);
        access.requireAppointmentWrite(appointment.petId());
        repository.lockPetAndVet(appointment.petId(), appointment.vetId());
        checkAvailability(appointment);
        return toDto(required(repository.insert(appointment), false));
    }

    public AppointmentDto update(int id, AppointmentFieldsDto request) {
        Appointment appointment = prepare(id, request);
        Appointment existing = required(id, true);
        access.requireAppointmentRead(existing);
        access.requireAppointmentWrite(appointment.petId());
        if (!"PENDING".equals(existing.status()) || !existing.startTime().isAfter(OffsetDateTime.now())) {
            throw error(HttpStatus.CONFLICT, "Only upcoming pending appointments can be changed");
        }
        repository.lockPetAndVet(appointment.petId(), appointment.vetId());
        checkAvailability(appointment);
        repository.update(appointment);
        return toDto(required(id, false));
    }

    public AppointmentDto cancel(int id) {
        Appointment existing = required(id, true);
        access.requireAppointmentRead(existing);
        if ("CANCELLED".equals(existing.status())) {
            return toDto(existing);
        }
        if (!existing.startTime().isAfter(OffsetDateTime.now())) {
            throw error(HttpStatus.CONFLICT, "An appointment that has started cannot be cancelled");
        }
        repository.cancel(id);
        return toDto(required(id, false));
    }

    public AppointmentDto confirm(int id) {
        Appointment existing = required(id, true);
        access.requireAppointmentConfirm(existing);
        if ("CONFIRMED".equals(existing.status())) {
            return toDto(existing);
        }
        if (!"PENDING".equals(existing.status())) {
            throw error(HttpStatus.CONFLICT, "Only pending appointments can be confirmed");
        }
        if (!existing.startTime().isAfter(OffsetDateTime.now())) {
            throw error(HttpStatus.CONFLICT, "An appointment that has started cannot be confirmed");
        }
        repository.confirm(id);
        return toDto(required(id, false));
    }

    private Appointment prepare(Integer id, AppointmentFieldsDto request) {
        if (request.getPetId() == null || request.getVetId() == null || request.getPetId() < 1 || request.getVetId() < 1
                || request.getStartTime() == null || request.getEndTime() == null
                || request.getReason() == null || request.getReason().isBlank() || request.getReason().length() > 255) {
            throw error(HttpStatus.BAD_REQUEST, "A pet, vet, startTime, endTime and non-blank reason (max 255 characters) are required");
        }
        OffsetDateTime start = request.getStartTime().withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        OffsetDateTime end = request.getEndTime().withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        if (!start.isAfter(OffsetDateTime.now()) || !end.isAfter(start)) {
            throw error(HttpStatus.BAD_REQUEST, "startTime must be in the future and endTime must be later than startTime");
        }
        return new Appointment(id, request.getPetId(), request.getVetId(), null, start, end, request.getReason(), "PENDING");
    }

    private void checkAvailability(Appointment appointment) {
        if (repository.overlaps(appointment.id(), appointment.petId(), appointment.vetId(), appointment.startTime(), appointment.endTime())) {
            throw error(HttpStatus.CONFLICT, "The pet or vet already has an overlapping appointment");
        }
    }

    private Appointment required(int id, boolean lock) {
        Appointment appointment = repository.findById(id, lock);
        if (appointment == null) {
            throw error(HttpStatus.NOT_FOUND, "Appointment not found");
        }
        return appointment;
    }

    private AppointmentDto toDto(Appointment appointment) {
        return new AppointmentDto().id(appointment.id()).petId(appointment.petId()).vetId(appointment.vetId())
            .ownerId(appointment.ownerId()).startTime(appointment.startTime()).endTime(appointment.endTime())
            .reason(appointment.reason()).status(appointment.status());
    }

    private static ResponseStatusException error(HttpStatus status, String reason) {
        return new ResponseStatusException(status, reason);
    }
}
