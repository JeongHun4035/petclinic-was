package org.springframework.samples.petclinic.rest.controller;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@ActiveProfiles(profiles = {"h2", "spring-data-jpa"}, inheritProfiles = false)
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:auth-${random.uuid};DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
class AuthSecurityH2Tests extends AuthSecurityTests {
}
