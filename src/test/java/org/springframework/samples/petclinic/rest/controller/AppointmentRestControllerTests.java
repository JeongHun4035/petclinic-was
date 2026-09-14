package org.springframework.samples.petclinic.rest.controller;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.samples.petclinic.repository.jdbc.JdbcAppointmentRepository;
import org.springframework.samples.petclinic.rest.advice.ExceptionControllerAdvice;
import org.springframework.samples.petclinic.rest.controller.v1.AppointmentRestControllerV1;
import org.springframework.samples.petclinic.rest.dto.AppointmentFieldsDto;
import org.springframework.samples.petclinic.service.AppointmentService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Exercises real SQL, transactions and HTTP contracts against both embedded DBs. */
class AppointmentRestControllerTests {
    private static final OffsetDateTime START = OffsetDateTime.now(ZoneOffset.ofHours(9)).plusDays(7).withNano(0);

    @ParameterizedTest
    @ValueSource(strings = {"h2", "hsqldb"})
    void createReadFilterAndReschedule(String platform) throws Exception {
        try (Fixture f = new Fixture(platform)) {
            var created = f.service.create(request(1, 1, START, START.plusMinutes(30)));
            assertEquals("SCHEDULED", created.getStatus());
            assertEquals(1, created.getOwnerId());
            assertEquals(START.toInstant(), created.getStartTime().toInstant());
            assertEquals(ZoneOffset.UTC, created.getStartTime().getOffset());
            f.mvc.perform(get("/api/appointments/{id}", created.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.petId").value(1))
                .andExpect(jsonPath("$.ownerId").value(1)).andExpect(jsonPath("$.status").value("SCHEDULED"));
            f.mvc.perform(get("/api/appointments").param("ownerId", "1").param("vetId", "1")
                    .param("from", START.plusMinutes(10).toString()).param("to", START.plusMinutes(20).toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
            f.mvc.perform(get("/api/appointments").param("ownerId", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
            f.mvc.perform(put("/api/appointments/{id}", created.getId()).contentType(MediaType.APPLICATION_JSON)
                    .content(json(1, 2, START.plusHours(1), START.plusHours(2))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.vetId").value(2));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"h2", "hsqldb"})
    void creationReturnsLocationAndDerivesOwner(String platform) throws Exception {
        try (Fixture f = new Fixture(platform)) {
            f.mvc.perform(post("/api/appointments").contentType(MediaType.APPLICATION_JSON)
                    .content(json(1, 1, START, START.plusMinutes(30))))
                .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.ownerId").value(1)).andExpect(jsonPath("$.status").value("SCHEDULED"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"h2", "hsqldb"})
    void overlappingPetOrVetIsRejectedButAdjacentBookingsAreAllowed(String platform) throws Exception {
        try (Fixture f = new Fixture(platform)) {
            f.service.create(request(1, 1, START, START.plusMinutes(30)));
            assertConflict(() -> f.service.create(request(2, 1, START.plusMinutes(10), START.plusMinutes(40))));
            assertConflict(() -> f.service.create(request(1, 2, START.minusMinutes(10), START.plusMinutes(10))));
            assertConflict(() -> f.service.create(request(2, 1, START.minusMinutes(10), START.plusMinutes(40))));
            assertConflict(() -> f.service.create(request(2, 1, START.plusMinutes(5), START.plusMinutes(10))));
            f.service.create(request(2, 2, START, START.plusMinutes(30)));
            f.service.create(request(1, 1, START.plusMinutes(30), START.plusMinutes(60)));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"h2", "hsqldb"})
    void updateExcludesItselfAndRollsBackOnConflict(String platform) throws Exception {
        try (Fixture f = new Fixture(platform)) {
            var first = f.service.create(request(1, 1, START, START.plusMinutes(30)));
            f.service.update(first.getId(), request(1, 1, START, START.plusMinutes(30)));
            f.service.create(request(2, 2, START, START.plusMinutes(30)));
            assertConflict(() -> f.service.update(first.getId(), request(1, 2, START, START.plusMinutes(30))));
            assertEquals(1, f.service.get(first.getId()).getVetId());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"h2", "hsqldb"})
    void cancellationIsIdempotentAndReleasesTime(String platform) throws Exception {
        try (Fixture f = new Fixture(platform)) {
            var first = f.service.create(request(1, 1, START, START.plusMinutes(30)));
            f.mvc.perform(post("/api/appointments/{id}/cancel", first.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
            assertEquals("CANCELLED", f.service.cancel(first.getId()).getStatus());
            assertConflict(() -> f.service.update(first.getId(), request(1, 1, START, START.plusMinutes(30))));
            f.service.create(request(1, 1, START, START.plusMinutes(30)));
            f.mvc.perform(get("/api/appointments").param("status", "CANCELLED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"h2", "hsqldb"})
    void invalidRequestsAndMissingResourcesReturnCorrectStatus(String platform) throws Exception {
        try (Fixture f = new Fixture(platform)) {
            for (String body : List.of("{}", "{", json(1, 1, START, START),
                    json(1, 1, START.minusYears(1), START.minusYears(1).plusMinutes(30)),
                    json(1, 1, START, START.plusMinutes(30)).replace("Vaccination", "   "),
                    json(1, 1, START, START.plusMinutes(30)).replace(START.toString(), "invalid-date"))) {
                f.mvc.perform(post("/api/appointments").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
            }
            for (int[] ids : List.of(new int[]{9999, 1}, new int[]{1, 9999})) {
                f.mvc.perform(post("/api/appointments").contentType(MediaType.APPLICATION_JSON)
                        .content(json(ids[0], ids[1], START, START.plusMinutes(30))))
                    .andExpect(status().isNotFound());
            }
            f.mvc.perform(get("/api/appointments/9999")).andExpect(status().isNotFound());
            f.mvc.perform(post("/api/appointments/9999/cancel")).andExpect(status().isNotFound());
            f.mvc.perform(get("/api/appointments").param("limit", "101")).andExpect(status().isBadRequest());
            f.mvc.perform(get("/api/appointments").param("offset", "-1")).andExpect(status().isBadRequest());
            f.mvc.perform(get("/api/appointments").param("status", "UNKNOWN")).andExpect(status().isBadRequest());
            f.mvc.perform(get("/api/appointments").param("from", "invalid-date")).andExpect(status().isBadRequest());
            f.mvc.perform(get("/api/appointments").param("from", START.toString()).param("to", START.toString()))
                .andExpect(status().isBadRequest());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"h2", "hsqldb"})
    void paginationAndTimezoneEquivalentRequests(String platform) throws Exception {
        try (Fixture f = new Fixture(platform)) {
            var first = f.service.create(request(1, 1, START, START.plusMinutes(30)));
            var second = f.service.create(request(1, 1, START.plusMinutes(30), START.plusMinutes(60)));
            assertConflict(() -> f.service.create(request(2, 1, START.withOffsetSameInstant(ZoneOffset.UTC),
                START.plusMinutes(30).withOffsetSameInstant(ZoneOffset.UTC))));
            var page = f.service.list(null, null, null, null, null, null, 1, 1);
            assertEquals(1, page.size());
            assertEquals(second.getId(), page.get(0).getId());
            assertEquals(first.getId(), f.service.list(null, null, null, null, null, START.plusMinutes(30), 20, 0).get(0).getId());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"h2", "hsqldb"})
    void pastAppointmentsCannotBeChangedOrCancelled(String platform) throws Exception {
        try (Fixture f = new Fixture(platform)) {
            var first = f.service.create(request(1, 1, START, START.plusMinutes(30)));
            new JdbcTemplate(f.database).update("UPDATE appointments SET start_time = ?, end_time = ? WHERE id = ?",
                java.sql.Timestamp.valueOf("2000-01-01 10:00:00"), java.sql.Timestamp.valueOf("2000-01-01 10:30:00"), first.getId());
            assertConflict(() -> f.service.cancel(first.getId()));
            assertConflict(() -> f.service.update(first.getId(), request(1, 1, START, START.plusMinutes(30))));
        }
    }

    @ParameterizedTest
    @CsvSource({"h2,1,1", "h2,2,1", "h2,1,2", "hsqldb,1,1", "hsqldb,2,1", "hsqldb,1,2"})
    void concurrentRequestsCannotDoubleBook(String platform, int secondPet, int secondVet) throws Exception {
        try (Fixture f = new Fixture(platform)) {
            var executor = Executors.newFixedThreadPool(2);
            var start = new CountDownLatch(1);
            try {
                Callable<Integer> book = () -> concurrentBook(f, start, 1, 1);
                var first = executor.submit(book);
                var second = executor.submit(() -> concurrentBook(f, start, secondPet, secondVet));
                start.countDown();
                assertEquals(List.of(201, 409), java.util.stream.Stream.of(
                    first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)).sorted().toList());
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void hsqlSchemaCanBeReappliedWithoutDeletingAppointments() {
        try (Fixture f = new Fixture("hsqldb")) {
            var created = f.service.create(request(1, 1, START, START.plusMinutes(30)));
            new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                new org.springframework.core.io.ClassPathResource("db/hsqldb/schema.sql")).execute(f.database);
            assertEquals(created.getId(), f.service.get(created.getId()).getId());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"h2", "hsqldb"})
    void jdbcRoundTripPreservesInstantsAcrossDstAndNormalizesPrecision(String platform) {
        try (Fixture f = new Fixture(platform)) {
            var dstDate = java.time.LocalDate.of(OffsetDateTime.now().getYear() + 1, 3, 1)
                .with(java.time.temporal.TemporalAdjusters.dayOfWeekInMonth(2, java.time.DayOfWeek.SUNDAY));
            var start = dstDate.atTime(2, 30).atOffset(ZoneOffset.UTC).withNano(123456789);
            var created = f.service.create(request(1, 1, start, start.plusMinutes(30)));
            assertEquals(start.toInstant().truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                created.getStartTime().toInstant());
        }
    }

    private static int concurrentBook(Fixture fixture, CountDownLatch start, int petId, int vetId) throws Exception {
        assertTrue(start.await(10, TimeUnit.SECONDS));
        try {
            fixture.service.create(request(petId, vetId, START, START.plusMinutes(30)));
            return 201;
        } catch (ResponseStatusException e) {
            return e.getStatusCode().value();
        }
    }

    private static void assertConflict(org.junit.jupiter.api.function.Executable action) {
        assertEquals(409, assertThrows(ResponseStatusException.class, action).getStatusCode().value());
    }

    private static AppointmentFieldsDto request(int petId, int vetId, OffsetDateTime start, OffsetDateTime end) {
        return new AppointmentFieldsDto().petId(petId).vetId(vetId).startTime(start).endTime(end).reason("Vaccination");
    }

    private static String json(int petId, int vetId, OffsetDateTime start, OffsetDateTime end) {
        return """
            {"petId":%d,"vetId":%d,"startTime":"%s","endTime":"%s","reason":"Vaccination"}
            """.formatted(petId, vetId, start, end);
    }

    private static class Fixture implements AutoCloseable {
        final EmbeddedDatabase database;
        final AppointmentService service;
        final MockMvc mvc;

        Fixture(String platform) {
            database = new EmbeddedDatabaseBuilder().generateUniqueName(true)
                .setType(platform.equals("h2") ? EmbeddedDatabaseType.H2 : EmbeddedDatabaseType.HSQL)
                .addScript("db/" + platform + "/schema.sql").addScript("db/" + platform + "/data.sql").build();
            var factory = new ProxyFactory(new AppointmentService(new JdbcAppointmentRepository(database),
                new org.springframework.samples.petclinic.security.AccessPolicy(
                    new org.springframework.samples.petclinic.repository.jdbc.JdbcAccountRepository(database), false)));
            factory.setProxyTargetClass(true);
            factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(database),
                new AnnotationTransactionAttributeSource()));
            service = (AppointmentService) factory.getProxy();
            mvc = MockMvcBuilders.standaloneSetup(new AppointmentRestControllerV1(service))
                .setControllerAdvice(new ExceptionControllerAdvice()).build();
        }

        @Override
        public void close() {
            database.shutdown();
        }
    }
}
