package org.springframework.samples.petclinic.repository.jdbc;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.http.HttpStatus;
import org.springframework.samples.petclinic.model.Appointment;
import org.springframework.samples.petclinic.repository.AppointmentRepository;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

/** Uses the shared datasource for all three existing repository profiles. */
@Repository
@DependsOnDatabaseInitialization
public class JdbcAppointmentRepository implements AppointmentRepository {
    private static final String SELECT = """
        SELECT a.id, a.pet_id, a.vet_id, p.owner_id, a.start_time, a.end_time, a.reason, a.status
        FROM appointments a JOIN pets p ON p.id = a.pet_id
        """;
    private static final RowMapper<Appointment> ROW_MAPPER = (rs, row) -> new Appointment(
        rs.getInt("id"), rs.getInt("pet_id"), rs.getInt("vet_id"), rs.getInt("owner_id"),
        rs.getObject("start_time", LocalDateTime.class).atOffset(ZoneOffset.UTC),
        rs.getObject("end_time", LocalDateTime.class).atOffset(ZoneOffset.UTC),
        rs.getString("reason"), rs.getString("status"));

    private final NamedParameterJdbcTemplate jdbc;
    private final SimpleJdbcInsert insert;

    public JdbcAppointmentRepository(DataSource dataSource) {
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        insert = new SimpleJdbcInsert(dataSource).withTableName("appointments")
            .usingColumns("pet_id", "vet_id", "start_time", "end_time", "reason", "status")
            .usingGeneratedKeyColumns("id");
    }

    @Override
    public Appointment findById(int id, boolean lock) {
        var parameters = new MapSqlParameterSource("id", id);
        if (lock && jdbc.queryForList("SELECT id FROM appointments WHERE id = :id FOR UPDATE", parameters).isEmpty()) {
            return null;
        }
        return jdbc.query(SELECT + " WHERE a.id = :id", parameters, ROW_MAPPER).stream().findFirst().orElse(null);
    }

    @Override
    public List<Appointment> findAll(Integer petId, Integer vetId, Integer ownerId, String status,
                                     OffsetDateTime from, OffsetDateTime to, int limit, int offset) {
        var sql = new StringBuilder(SELECT).append(" WHERE 1 = 1");
        var parameters = new MapSqlParameterSource().addValue("limit", limit).addValue("offset", offset);
        if (petId != null) { sql.append(" AND a.pet_id = :petId"); parameters.addValue("petId", petId); }
        if (vetId != null) { sql.append(" AND a.vet_id = :vetId"); parameters.addValue("vetId", vetId); }
        if (ownerId != null) { sql.append(" AND p.owner_id = :ownerId"); parameters.addValue("ownerId", ownerId); }
        if (status != null) { sql.append(" AND a.status = :status"); parameters.addValue("status", status); }
        if (from != null) { sql.append(" AND a.end_time > :from"); parameters.addValue("from", utcTime(from), java.sql.Types.TIMESTAMP); }
        if (to != null) { sql.append(" AND a.start_time < :to"); parameters.addValue("to", utcTime(to), java.sql.Types.TIMESTAMP); }
        sql.append(" ORDER BY a.start_time, a.id LIMIT :limit OFFSET :offset");
        return jdbc.query(sql.toString(), parameters, ROW_MAPPER);
    }

    @Override
    public void lockPetAndVet(int petId, int vetId) {
        // Always lock pet before vet. This serializes conflicting creates/reschedules across server instances.
        if (jdbc.queryForList("SELECT id FROM pets WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource("id", petId)).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pet not found");
        }
        if (jdbc.queryForList("SELECT id FROM vets WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource("id", vetId)).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Vet not found");
        }
    }

    @Override
    public boolean overlaps(Integer excludedId, int petId, int vetId, OffsetDateTime start, OffsetDateTime end) {
        var parameters = new MapSqlParameterSource().addValue("excludedId", excludedId == null ? -1 : excludedId)
            .addValue("petId", petId).addValue("vetId", vetId)
            .addValue("start", utcTime(start), java.sql.Types.TIMESTAMP)
            .addValue("end", utcTime(end), java.sql.Types.TIMESTAMP);
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM appointments
            WHERE status = 'SCHEDULED' AND id <> :excludedId
              AND (pet_id = :petId OR vet_id = :vetId)
              AND start_time < :end AND end_time > :start
            """, parameters, Integer.class);
        return count != null && count > 0;
    }

    @Override
    public int insert(Appointment appointment) {
        return insert.executeAndReturnKey(parameters(appointment)).intValue();
    }

    @Override
    public void update(Appointment appointment) {
        jdbc.update("""
            UPDATE appointments SET pet_id = :pet_id, vet_id = :vet_id,
              start_time = :start_time, end_time = :end_time, reason = :reason
            WHERE id = :id
            """, parameters(appointment).addValue("id", appointment.id()));
    }

    @Override
    public void cancel(int id) {
        jdbc.update("UPDATE appointments SET status = 'CANCELLED' WHERE id = :id", new MapSqlParameterSource("id", id));
    }

    private MapSqlParameterSource parameters(Appointment appointment) {
        return new MapSqlParameterSource().addValue("pet_id", appointment.petId()).addValue("vet_id", appointment.vetId())
            .addValue("start_time", utcTime(appointment.startTime()), java.sql.Types.TIMESTAMP)
            .addValue("end_time", utcTime(appointment.endTime()), java.sql.Types.TIMESTAMP)
            .addValue("reason", appointment.reason()).addValue("status", appointment.status());
    }

    private static LocalDateTime utcTime(OffsetDateTime time) {
        // Store UTC wall-clock values so JDBC/DB default timezone does not change the appointment instant.
        return time.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}
