package org.springframework.samples.petclinic.security;

import java.util.List;

public record Account(String username, String password, boolean enabled, List<String> roles,
                      Integer ownerId, Integer vetId) {
    public String authCode() {
        if (roles.contains("ROLE_ADMIN")) return "ADMIN";
        if (roles.contains("ROLE_VET") || roles.contains("ROLE_VETS") || roles.contains("ROLE_VET_ADMIN")) return "VETS";
        if (roles.contains("ROLE_OWNER") || roles.contains("ROLE_OWNER_ADMIN")) return "OWNER";
        return null;
    }
}
