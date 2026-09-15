package org.springframework.samples.petclinic.repository.jdbc;

import java.util.List;
import javax.sql.DataSource;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.samples.petclinic.security.Account;
import org.springframework.samples.petclinic.rest.dto.SignupRequestDto;
import org.springframework.stereotype.Repository;

@Repository
@DependsOnDatabaseInitialization
public class JdbcAccountRepository {
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;
    private final JdbcTemplate jdbc;
    private final SimpleJdbcInsert owners;

    public JdbcAccountRepository(DataSource source) {
        jdbc = new JdbcTemplate(source);
        owners = new SimpleJdbcInsert(source).withTableName("owners")
            .usingColumns("first_name", "last_name", "address", "city", "telephone").usingGeneratedKeyColumns("id");
    }

    public Account find(String username) {
        List<Account> accounts = jdbc.query("""
            SELECT u.username, u.password, u.enabled, p.owner_id, p.vet_id
            FROM users u LEFT JOIN user_profiles p ON p.username = u.username WHERE u.username = ?
            """, (rs, row) -> new Account(rs.getString("username"), rs.getString("password"), rs.getBoolean("enabled"),
                List.of(), rs.getObject("owner_id", Integer.class), rs.getObject("vet_id", Integer.class)), username);
        if (accounts.isEmpty()) return null;
        Account account = accounts.get(0);
        List<String> roles = jdbc.queryForList("SELECT role FROM roles WHERE username = ? ORDER BY role", String.class, username);
        return new Account(account.username(), account.password(), account.enabled(), roles, account.ownerId(), account.vetId());
    }

    public int createOwner(SignupRequestDto request) {
        return owners.executeAndReturnKey(new MapSqlParameterSource()
            .addValue("first_name", request.getFirstName()).addValue("last_name", request.getLastName())
            .addValue("address", request.getAddress()).addValue("city", request.getCity())
            .addValue("telephone", request.getTelephone())).intValue();
    }

    public void createOwnerAccount(String username, String encodedPassword, int ownerId) {
        jdbc.update("INSERT INTO users(username, password, enabled) VALUES (?, ?, ?)", username, encodedPassword, true);
        jdbc.update("INSERT INTO roles(username, role) VALUES (?, 'ROLE_OWNER')", username);
        saveProfile(username, ownerId, null);
    }

    public void saveProfile(String username, Integer ownerId, Integer vetId) {
        jdbc.update("DELETE FROM user_profiles WHERE username = ?", username);
        jdbc.update("INSERT INTO user_profiles(username, owner_id, vet_id) VALUES (?, ?, ?)", username, ownerId, vetId);
    }

    public void saveProfileForNewUser(org.springframework.samples.petclinic.model.User user) {
        if (entityManager.isJoinedToTransaction()) entityManager.flush();
        saveProfile(user.getUsername(), user.getOwnerId(), user.getVetId());
    }

    public Integer petOwnerId(int petId) {
        return jdbc.queryForList("SELECT owner_id FROM pets WHERE id = ?", Integer.class, petId).stream().findFirst().orElse(null);
    }

    public Integer visitPetId(int visitId) {
        return jdbc.queryForList("SELECT pet_id FROM visits WHERE id = ?", Integer.class, visitId).stream().findFirst().orElse(null);
    }

    public boolean vetTreatsPet(int vetId, int petId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM appointments WHERE vet_id = ? AND pet_id = ? AND status IN ('PENDING', 'CONFIRMED')",
            Integer.class, vetId, petId);
        return count != null && count > 0;
    }

    public boolean vetTreatsOwner(int vetId, int ownerId) {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM appointments a JOIN pets p ON p.id = a.pet_id
            WHERE a.vet_id = ? AND p.owner_id = ? AND a.status IN ('PENDING', 'CONFIRMED')
            """, Integer.class, vetId, ownerId);
        return count != null && count > 0;
    }

    public boolean ownerExists(int id) {
        return !jdbc.queryForList("SELECT id FROM owners WHERE id = ?", Integer.class, id).isEmpty();
    }

    public boolean vetExists(int id) {
        return !jdbc.queryForList("SELECT id FROM vets WHERE id = ?", Integer.class, id).isEmpty();
    }
}
