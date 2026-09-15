package org.springframework.samples.petclinic.rest.controller.v1;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.samples.petclinic.rest.api.AppointmentsApi;
import org.springframework.samples.petclinic.rest.dto.AppointmentDto;
import org.springframework.samples.petclinic.rest.dto.AppointmentFieldsDto;
import org.springframework.samples.petclinic.service.AppointmentService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api")
@CrossOrigin(exposedHeaders = "errors, content-type, location")
@PreAuthorize("@access.clinicalUser()")
public class AppointmentRestControllerV1 implements AppointmentsApi {
    private final AppointmentService service;

    public AppointmentRestControllerV1(AppointmentService service) {
        this.service = service;
    }

    @Override
    public ResponseEntity<AppointmentDto> addAppointment(AppointmentFieldsDto request) {
        AppointmentDto appointment = service.create(request);
        return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
            .buildAndExpand(appointment.getId()).toUri()).body(appointment);
    }

    @Override
    public ResponseEntity<AppointmentDto> getAppointment(Integer appointmentId) {
        return ResponseEntity.ok(service.get(appointmentId));
    }

    @Override
    public ResponseEntity<List<AppointmentDto>> listAppointments(Integer petId, Integer vetId, Integer ownerId,
            String status, OffsetDateTime from, OffsetDateTime to, Integer limit, Integer offset) {
        return ResponseEntity.ok(service.list(petId, vetId, ownerId, status, from, to, limit, offset));
    }

    @Override
    public ResponseEntity<AppointmentDto> updateAppointment(Integer appointmentId, AppointmentFieldsDto request) {
        return ResponseEntity.ok(service.update(appointmentId, request));
    }

    @Override
    public ResponseEntity<AppointmentDto> cancelAppointment(Integer appointmentId) {
        return ResponseEntity.ok(service.cancel(appointmentId));
    }

    @Override
    public ResponseEntity<AppointmentDto> confirmAppointment(Integer appointmentId) {
        return ResponseEntity.ok(service.confirm(appointmentId));
    }
}
