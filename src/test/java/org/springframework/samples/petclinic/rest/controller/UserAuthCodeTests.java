package org.springframework.samples.petclinic.rest.controller;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mapstruct.factory.Mappers;
import org.springframework.http.MediaType;
import org.springframework.samples.petclinic.mapper.UserMapper;
import org.springframework.samples.petclinic.rest.controller.v1.UserRestControllerV1;
import org.springframework.samples.petclinic.service.UserService;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserAuthCodeTests {

    @ParameterizedTest
    @CsvSource({
        "OWNER_ADMIN, OWNER",
        "ROLE_OWNER_ADMIN, OWNER",
        "VET_ADMIN, VETS",
        "ROLE_VET_ADMIN, VETS",
        "ADMIN, ADMIN",
        "ROLE_ADMIN, ADMIN"
    })
    void creationAutomaticallyReturnsAuthCode(String role, String expectedCode) throws Exception {
        var controller = new UserRestControllerV1(mock(UserService.class), Mappers.getMapper(UserMapper.class));
        var mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        // A caller-provided code must not override the code derived from roles.
        String request = """
            {"username":"new-user","password":"password","enabled":true,
             "authCode":"CLIENT_VALUE","roles":[{"name":"%s"}]}
            """.formatted(role);

        mockMvc.perform(post("/api/users")
                .contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.authCode").value(expectedCode));
    }
}
