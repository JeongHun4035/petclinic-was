package org.springframework.samples.petclinic.rest.controller.v2;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.samples.petclinic.mapper.OwnerMapper;
import org.springframework.samples.petclinic.model.Owner;
import org.springframework.samples.petclinic.rest.api.OwnerV2Api;
import org.springframework.samples.petclinic.rest.dto.OwnerPageDto;
import org.springframework.samples.petclinic.service.ClinicService;
import org.springframework.samples.petclinic.security.AccessPolicy;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;

@RestController
@CrossOrigin(exposedHeaders = "errors, content-type")
@RequestMapping("/api")
public class OwnerRestControllerV2 implements OwnerV2Api {

    private final ClinicService clinicService;
    private final OwnerMapper ownerMapper;
    private final AccessPolicy access;

    public OwnerRestControllerV2(ClinicService clinicService, OwnerMapper ownerMapper, AccessPolicy access) {
        this.clinicService = clinicService;
        this.ownerMapper = ownerMapper;
        this.access = access;
    }

    @Override
    @PreAuthorize("@access.clinicalUser()")
    public ResponseEntity<OwnerPageDto> listOwnersPage(String lastName, Integer page, Integer size) {
        int pageNumber = page == null ? 0 : page;
        int pageSize = size == null ? 20 : size;
        if (pageNumber < 0 || pageSize < 1 || pageSize > 100) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid page or size (1-100)");
        }
        var pageable = PageRequest.of(pageNumber, pageSize, Sort.by("id"));
        Page<Owner> owners;
        if (access.clinicalStaff()) {
            owners = this.clinicService.findOwners(lastName, pageable);
        } else {
            var visible = access.visibleOwners(clinicService.findAllOwners()).stream()
                .filter(owner -> lastName == null || owner.getLastName().toLowerCase(java.util.Locale.ROOT)
                    .startsWith(lastName.toLowerCase(java.util.Locale.ROOT)))
                .sorted(java.util.Comparator.comparing(Owner::getId)).toList();
            int start = (int) Math.min(pageable.getOffset(), visible.size());
            owners = new org.springframework.data.domain.PageImpl<>(
                visible.subList(start, Math.min(start + pageSize, visible.size())), pageable, visible.size());
        }
        OwnerPageDto response = ownerMapper.toOwnerPageDto(owners);
        response.getContent().forEach(owner -> owner.setPets(owner.getPets().stream()
            .filter(pet -> access.readPet(pet.getId())).toList()));
        return ResponseEntity.ok(response);
    }
}
