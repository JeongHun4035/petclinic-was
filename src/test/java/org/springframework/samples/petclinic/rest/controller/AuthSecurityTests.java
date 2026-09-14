package org.springframework.samples.petclinic.rest.controller;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static com.jayway.jsonpath.JsonPath.read;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Uses the real SecurityFilterChain, real JWT signatures, database and method authorization. */
@SpringBootTest
@ActiveProfiles({"hsqldb", "spring-data-jpa"})
class AuthSecurityTests {
    private static final String PASSWORD = "PassWord123!";
    @Autowired WebApplicationContext context;
    @Autowired DataSource source;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtEncoder encoder;
    MockMvc mvc;
    JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc = new JdbcTemplate(source);
    }

    @Test
    void signupHashesPasswordLinksANewOwnerAndLoginReturnsToken() throws Exception {
        Session owner = owner();
        String stored = jdbc.queryForObject("SELECT password FROM users WHERE username = ?", String.class, owner.username());
        assertNotEquals(PASSWORD, stored);
        assertTrue(passwords.matches(PASSWORD, stored));
        assertEquals("ROLE_OWNER", jdbc.queryForObject("SELECT role FROM roles WHERE username = ?", String.class, owner.username()));
        mvc.perform(get("/api/auth/me").header("Authorization", bearer(owner.token())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.authCode").value("OWNER"))
            .andExpect(jsonPath("$.ownerId").value(owner.ownerId())).andExpect(jsonPath("$.password").doesNotExist());
        mvc.perform(get("/api/vets").header("Authorization", bearer(owner.token()))).andExpect(status().isOk());
    }

    @Test
    void publicSignupCannotGrantStaffRolesOrTakeOverExistingProfiles() throws Exception {
        for (String code : List.of("ADMIN", "VETS", "OWNER_ADMIN", "ROLE_ADMIN")) {
            mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(signupBody(username(), code)))
                .andExpect(status().isForbidden());
        }
        String username = username();
        String spoof = signupBody(username, "OWNER").replace("\"authCode\":\"OWNER\"", "\"authCode\":\"OWNER\",\"ownerId\":1,\"roles\":[{\"name\":\"ADMIN\"}]");
        var response = mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(spoof))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.authCode").value("OWNER")).andReturn();
        assertNotEquals(1, ((Number) read(response.getResponse().getContentAsString(), "$.ownerId")).intValue());
    }

    @Test
    void duplicateSignupDoesNotCreateAnExtraOwner() throws Exception {
        Session owner = owner();
        Integer before = jdbc.queryForObject("SELECT COUNT(*) FROM owners", Integer.class);
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(signupBody(owner.username(), "OWNER")))
            .andExpect(status().isConflict());
        assertEquals(before, jdbc.queryForObject("SELECT COUNT(*) FROM owners", Integer.class));
    }

    @Test
    void simultaneousSignupCreatesExactlyOneAccountAndOwner() throws Exception {
        String name = username();
        Integer before = jdbc.queryForObject("SELECT COUNT(*) FROM owners", Integer.class);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var ready = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Integer> signup = () -> {
                assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
                return mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                    .content(signupBody(name, "OWNER"))).andReturn().getResponse().getStatus();
            };
            var first = pool.submit(signup);
            var second = pool.submit(signup);
            ready.countDown();
            assertEquals(List.of(201, 409), java.util.stream.Stream.of(first.get(30, java.util.concurrent.TimeUnit.SECONDS),
                second.get(30, java.util.concurrent.TimeUnit.SECONDS)).sorted().toList());
            assertEquals(before + 1, jdbc.queryForObject("SELECT COUNT(*) FROM owners", Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void omittedAuthCodeDefaultsToOwnerAndBcryptByteLimitIsEnforced() throws Exception {
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content(signupBody(username(), "OWNER").replace("\"authCode\":\"OWNER\",", "")))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.authCode").value("OWNER"));
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content(signupBody(username(), "OWNER").replace(PASSWORD, "한".repeat(25))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void credentialsDisabledAccountsAndMalformedInputAreHandled() throws Exception {
        Session owner = owner();
        for (String name : List.of(owner.username(), "unknown-user")) {
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"" + name + "\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value("Invalid username or password"));
        }
        jdbc.update("UPDATE users SET enabled = ? WHERE username = ?", false, owner.username());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(loginBody(owner.username(), PASSWORD)))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header("Authorization", bearer(owner.token()))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(signupBody(username(), "OWNER").replace(PASSWORD, "short")))
            .andExpect(status().isBadRequest()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("short"))));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void missingInvalidTamperedExpiredAndWrongIssuerTokensAreRejected() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized());
        Session owner = owner();
        String token = owner.token();
        int signature = token.lastIndexOf('.') + 1;
        char replacement = token.charAt(signature) == 'a' ? 'b' : 'a';
        String tampered = token.substring(0, signature) + replacement + token.substring(signature + 1);
        for (String invalid : List.of("not-a-token", tampered,
                signed(owner.username(), "petclinic-rest", List.of("petclinic-api"), Instant.now().minusSeconds(60)),
                signed(owner.username(), "wrong-issuer", List.of("petclinic-api"), Instant.now().plusSeconds(60)),
                signed(owner.username(), "petclinic-rest", List.of("wrong-api"), Instant.now().plusSeconds(60)))) {
            mvc.perform(get("/api/auth/me").header("Authorization", bearer(invalid)))
                .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void tokenClaimsCannotOverrideCurrentDatabaseRoles() throws Exception {
        Session owner = owner();
        // Even a correctly signed token with stale/escalated roles must use current DB authorities.
        String elevated = signed(owner.username(), "petclinic-rest", List.of("petclinic-api"), Instant.now().plusSeconds(60));
        mvc.perform(post("/api/users").header("Authorization", bearer(elevated)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"attacker\",\"password\":\"PassWord123!\",\"roles\":[{\"name\":\"ADMIN\"}]}"))
            .andExpect(status().isForbidden());
        jdbc.update("DELETE FROM roles WHERE username = ?", owner.username());
        mvc.perform(get("/api/appointments").header("Authorization", bearer(owner.token()))).andExpect(status().isForbidden());
    }

    @Test
    void adminCanCreateLinkedVetAndAdminAccountsWithoutPasswordDisclosure() throws Exception {
        String admin = login("admin", "admin");
        String vetUsername = username();
        int vetId = vet(admin);
        mvc.perform(post("/api/users").header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                .content(staffBody(vetUsername, vetId)))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.authCode").value("VETS"))
            .andExpect(jsonPath("$.vetId").value(vetId)).andExpect(jsonPath("$.password").doesNotExist());
        String vetToken = login(vetUsername, PASSWORD);
        mvc.perform(get("/api/auth/me").header("Authorization", bearer(vetToken)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.authCode").value("VETS"));
        mvc.perform(post("/api/users").header("Authorization", bearer(vetToken)).contentType(MediaType.APPLICATION_JSON).content(staffBody(username(), vetId)))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/users").header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON).content(staffBody(username(), vetId)))
            .andExpect(status().isConflict());
        String newAdmin = username();
        mvc.perform(post("/api/users").header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + newAdmin + "\",\"password\":\"" + PASSWORD + "\",\"roles\":[{\"name\":\"ADMIN\"}]}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.authCode").value("ADMIN"));
        mvc.perform(get("/api/vets").header("Authorization", bearer(login(newAdmin, PASSWORD)))).andExpect(status().isOk());
    }

    @Test
    void ownersCannotReadOtherProfilesOrBypassNestedPetPermissions() throws Exception {
        Session first = owner();
        Session second = owner();
        int secondPet = pet(second);
        mvc.perform(get("/api/owners/{id}", second.ownerId()).header("Authorization", bearer(first.token())))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/pets/{id}", secondPet).header("Authorization", bearer(first.token())))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/owners/{owner}/pets/{pet}", first.ownerId(), secondPet).header("Authorization", bearer(first.token())))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/owners").header("Authorization", bearer(first.token())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(first.ownerId()));
        mvc.perform(get("/api/v2/owners").header("Authorization", bearer(first.token())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/v2/pets").header("Authorization", bearer(first.token())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void reservationsAreScopedToOwnerAndAssignedVet() throws Exception {
        Session first = owner();
        Session second = owner();
        int firstPet = pet(first);
        int siblingPet = pet(first);
        int secondPet = pet(second);
        String admin = login("admin", "admin");
        int firstVet = vet(admin);
        int secondVet = vet(admin);
        String vetName = username();
        mvc.perform(post("/api/users").header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON).content(staffBody(vetName, firstVet)))
            .andExpect(status().isCreated());
        String vetToken = login(vetName, PASSWORD);
        int firstBooking = appointment(first, firstPet, firstVet);
        int secondBooking = appointment(second, secondPet, secondVet);
        for (String token : List.of(first.token(), vetToken)) {
            mvc.perform(get("/api/appointments/{id}", secondBooking).header("Authorization", bearer(token))).andExpect(status().isForbidden());
            mvc.perform(post("/api/appointments/{id}/cancel", secondBooking).header("Authorization", bearer(token))).andExpect(status().isForbidden());
            mvc.perform(get("/api/appointments").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        }
        mvc.perform(get("/api/appointments").param("ownerId", second.ownerId().toString()).header("Authorization", bearer(first.token())))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/appointments").param("vetId", Integer.toString(secondVet)).header("Authorization", bearer(vetToken)))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/appointments").header("Authorization", bearer(first.token())).contentType(MediaType.APPLICATION_JSON).content(appointmentBody(secondPet, secondVet)))
            .andExpect(status().isForbidden());
        mvc.perform(put("/api/appointments/{id}", firstBooking).header("Authorization", bearer(first.token())).contentType(MediaType.APPLICATION_JSON).content(appointmentBody(secondPet, secondVet)))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/pets/{id}", firstPet).header("Authorization", bearer(vetToken))).andExpect(status().isOk());
        mvc.perform(get("/api/pets/{id}", siblingPet).header("Authorization", bearer(vetToken))).andExpect(status().isForbidden());
        mvc.perform(get("/api/owners/{id}", first.ownerId()).header("Authorization", bearer(vetToken)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.pets.length()").value(1));
        mvc.perform(post("/api/appointments/{id}/cancel", firstBooking).header("Authorization", bearer(vetToken)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void corsAllowsAuthorizationHeaderAndDocumentationIsPublic() throws Exception {
        mvc.perform(options("/api/auth/login").header("Origin", "http://localhost:4200")
                .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "authorization,content-type"))
            .andExpect(status().isOk()).andExpect(header().exists("Access-Control-Allow-Origin"));
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
            .andExpect(jsonPath("$.paths['/api/auth/signup'].post.security").isEmpty())
            .andExpect(jsonPath("$.paths['/api/auth/login'].post.security").isEmpty());
    }

    Session owner() throws Exception {
        String username = username();
        var result = mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(signupBody(username, "OWNER")))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.password").doesNotExist()).andReturn();
        Integer id = ((Number) read(result.getResponse().getContentAsString(), "$.ownerId")).intValue();
        return new Session(username, id, login(username, PASSWORD));
    }

    String login(String username, String password) throws Exception {
        var result = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(loginBody(username, password)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.expiresIn").value(3600)).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.user.password").doesNotExist()).andReturn();
        return read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    int pet(Session owner) throws Exception {
        var result = mvc.perform(post("/api/owners/{id}/pets", owner.ownerId()).header("Authorization", bearer(owner.token()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Patient\",\"birthDate\":\"2020-01-01\",\"type\":{\"id\":2,\"name\":\"dog\"}}"))
            .andExpect(status().isCreated()).andReturn();
        return ((Number) read(result.getResponse().getContentAsString(), "$.id")).intValue();
    }

    int vet(String admin) throws Exception {
        var result = mvc.perform(post("/api/vets").header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"firstName\":\"Test\",\"lastName\":\"Vet\",\"specialties\":[]}"))
            .andExpect(status().isCreated()).andReturn();
        return ((Number) read(result.getResponse().getContentAsString(), "$.id")).intValue();
    }

    int appointment(Session owner, int pet, int vet) throws Exception {
        var result = mvc.perform(post("/api/appointments").header("Authorization", bearer(owner.token()))
                .contentType(MediaType.APPLICATION_JSON).content(appointmentBody(pet, vet)))
            .andExpect(status().isCreated()).andReturn();
        return ((Number) read(result.getResponse().getContentAsString(), "$.id")).intValue();
    }

    String signed(String subject, String issuer, List<String> audience, Instant expires) {
        var claims = JwtClaimsSet.builder().subject(subject).issuer(issuer).audience(audience)
            .issuedAt(Instant.now().minusSeconds(120)).expiresAt(expires).claim("roles", List.of("ROLE_ADMIN")).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    static String username() { return "user-" + UUID.randomUUID().toString().substring(0, 8); }
    static String bearer(String token) { return "Bearer " + token; }
    static String loginBody(String username, String password) { return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"; }
    static String signupBody(String username, String code) {
        return """
            {"username":"%s","password":"%s","authCode":"%s","firstName":"Owner","lastName":"Tester",
             "address":"123 Test Street","city":"Seoul","telephone":"6085551023"}
            """.formatted(username, PASSWORD, code);
    }
    static String staffBody(String username, int vetId) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\",\"vetId\":" + vetId + ",\"roles\":[{\"name\":\"VET\"}]}";
    }
    static String appointmentBody(int petId, int vetId) {
        var start = OffsetDateTime.now().plusDays(2).withNano(0);
        return "{\"petId\":" + petId + ",\"vetId\":" + vetId + ",\"startTime\":\"" + start + "\",\"endTime\":\"" + start.plusMinutes(30) + "\",\"reason\":\"Checkup\"}";
    }
    record Session(String username, Integer ownerId, String token) {}
}
