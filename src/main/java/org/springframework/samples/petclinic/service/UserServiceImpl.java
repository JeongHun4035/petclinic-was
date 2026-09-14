package org.springframework.samples.petclinic.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.samples.petclinic.model.User;
import org.springframework.samples.petclinic.model.Role;
import org.springframework.samples.petclinic.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.samples.petclinic.repository.jdbc.JdbcAccountRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class UserServiceImpl implements UserService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcAccountRepository accounts;

    @Autowired
    private PasswordEncoder passwords;

    @Override
    @Transactional
    public void saveUser(User user) {

        if (user.getUsername() == null || !user.getUsername().matches("[A-Za-z0-9._-]{3,20}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username must use 3-20 letters, digits, dots, underscores or hyphens");
        }

        if (accounts.find(user.getUsername()) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
        }
        AuthService.validatePassword(user.getPassword());

        if(user.getRoles() == null || user.getRoles().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "User must have at least a role set");
        }

        for (Role role : user.getRoles()) {
            if (role == null || role.getName() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Role name is required");
            }
            if(!role.getName().startsWith("ROLE_")) {
                role.setName("ROLE_" + role.getName());
            }
            if (role.getName().equals("ROLE_VETS")) role.setName("ROLE_VET");
            if (!java.util.Set.of("ROLE_OWNER", "ROLE_VET", "ROLE_ADMIN", "ROLE_OWNER_ADMIN", "ROLE_VET_ADMIN").contains(role.getName())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported role");
            }
            if (role.getName().equals("ROLE_OWNER") && user.getOwnerId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OWNER account requires ownerId");
            }
            if (role.getName().equals("ROLE_VET") && user.getVetId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VET account requires vetId");
            }

            if(role.getUser() == null) {
                role.setUser(user);
            }
        }

        if (user.getOwnerId() != null && !accounts.ownerExists(user.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Owner not found");
        }
        if (user.getVetId() != null && !accounts.vetExists(user.getVetId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Vet not found");
        }
        user.setPassword(passwords.encode(user.getPassword()));
        if (user.getEnabled() == null) user.setEnabled(true);
        try {
            userRepository.save(user);
            // Flush JPA inserts before the JDBC profile FK is inserted; JDBC repositories are already synchronous.
            accounts.saveProfileForNewUser(user);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username or linked profile already exists");
        }
    }
}
