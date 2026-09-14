package org.springframework.samples.petclinic.security;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.samples.petclinic.model.Appointment;
import org.springframework.samples.petclinic.model.Owner;
import org.springframework.samples.petclinic.model.Pet;
import org.springframework.samples.petclinic.model.Visit;
import org.springframework.samples.petclinic.repository.jdbc.JdbcAccountRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component("access")
public class AccessPolicy {
    private final JdbcAccountRepository accounts;
    private final boolean enabled;

    public AccessPolicy(JdbcAccountRepository accounts, @Value("${petclinic.security.enable:true}") boolean enabled) {
        this.accounts = accounts;
        this.enabled = enabled;
    }

    public boolean hasRole(String role) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals(role));
    }

    public boolean managesOwners() { return !enabled || hasRole("ROLE_ADMIN") || hasRole("ROLE_OWNER_ADMIN"); }
    public boolean managesVets() { return !enabled || hasRole("ROLE_ADMIN") || hasRole("ROLE_VET_ADMIN"); }
    public boolean clinicalStaff() { return managesOwners() || managesVets(); }
    public boolean clinicalUser() { return clinicalStaff() || hasRole("ROLE_OWNER") || hasRole("ROLE_VET"); }

    public Account currentAccount() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        Account account = accounts.find(auth.getName());
        if (account == null || !account.enabled()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid account");
        return account;
    }

    public boolean readOwner(Integer ownerId) {
        if (clinicalStaff()) return true;
        if (ownerId == null) return false;
        Account account = currentAccount();
        return (hasRole("ROLE_OWNER") && Objects.equals(ownerId, account.ownerId()))
            || (hasRole("ROLE_VET") && account.vetId() != null && accounts.vetTreatsOwner(account.vetId(), ownerId));
    }

    public boolean writeOwner(Integer ownerId) {
        return managesOwners() || (hasRole("ROLE_OWNER") && ownerId != null && Objects.equals(currentAccount().ownerId(), ownerId));
    }

    public boolean readPet(Integer petId) {
        if (clinicalStaff()) return true;
        if (petId == null) return false;
        Integer ownerId = accounts.petOwnerId(petId);
        Account account = currentAccount();
        return (hasRole("ROLE_OWNER") && ownerId != null && Objects.equals(ownerId, account.ownerId()))
            || (hasRole("ROLE_VET") && account.vetId() != null && accounts.vetTreatsPet(account.vetId(), petId));
    }

    public boolean writePet(Integer petId) {
        return managesOwners() || (petId != null && writeOwner(accounts.petOwnerId(petId)));
    }

    public boolean ownerPet(Integer ownerId, Integer petId) {
        if (managesOwners()) return true;
        return petId != null && ownerId != null && Objects.equals(accounts.petOwnerId(petId), ownerId);
    }

    public boolean readVisit(Integer visitId) {
        return clinicalStaff() || (visitId != null && readPet(accounts.visitPetId(visitId)));
    }

    public boolean writeVisitForPet(Integer petId) {
        return managesOwners() || (hasRole("ROLE_VET") && petId != null && currentAccount().vetId() != null
            && accounts.vetTreatsPet(currentAccount().vetId(), petId));
    }

    public boolean writeVisit(Integer visitId) {
        return managesOwners() || (visitId != null && writeVisitForPet(accounts.visitPetId(visitId)));
    }

    public List<Owner> visibleOwners(Collection<Owner> values) { return values.stream().filter(v -> readOwner(v.getId())).toList(); }
    public List<Pet> visiblePets(Collection<Pet> values) { return values.stream().filter(v -> readPet(v.getId())).toList(); }
    public List<Visit> visibleVisits(Collection<Visit> values) { return values.stream().filter(v -> readPet(v.getPet().getId())).toList(); }

    public void requireAppointmentRead(Appointment appointment) {
        if (clinicalStaff()) return;
        Account account = currentAccount();
        if ((hasRole("ROLE_OWNER") && Objects.equals(account.ownerId(), appointment.ownerId()) && account.ownerId() != null)
                || (hasRole("ROLE_VET") && Objects.equals(account.vetId(), appointment.vetId()) && account.vetId() != null)) return;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This appointment belongs to another account");
    }

    public void requireAppointmentWrite(Integer petId) {
        if (managesOwners()) return;
        if (hasRole("ROLE_OWNER") && writePet(petId)) return;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the pet owner or administrator can book or reschedule");
    }
}
