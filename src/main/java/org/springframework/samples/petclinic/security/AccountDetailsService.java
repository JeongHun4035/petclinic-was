package org.springframework.samples.petclinic.security;

import org.springframework.samples.petclinic.repository.jdbc.JdbcAccountRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AccountDetailsService implements UserDetailsService {
    private final JdbcAccountRepository repository;

    public AccountDetailsService(JdbcAccountRepository repository) {
        this.repository = repository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        Account account = repository.find(username);
        if (account == null) throw new UsernameNotFoundException("Invalid credentials");
        return org.springframework.security.core.userdetails.User.withUsername(account.username())
            .password(account.password()).disabled(!account.enabled()).authorities(account.roles().toArray(String[]::new)).build();
    }
}
