package org.springframework.samples.petclinic.rest.controller.v2;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.samples.petclinic.mapper.PetMapper;
import org.springframework.samples.petclinic.model.Pet;
import org.springframework.samples.petclinic.rest.api.PetV2Api;
import org.springframework.samples.petclinic.rest.dto.PetPageDto;
import org.springframework.samples.petclinic.service.ClinicService;
import org.springframework.samples.petclinic.security.AccessPolicy;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;

@RestController
@CrossOrigin(exposedHeaders = "errors, content-type")
@RequestMapping("/api")
public class PetRestControllerV2 implements PetV2Api {

    private final ClinicService clinicService;
    private final PetMapper petMapper;
    private final AccessPolicy access;

    public PetRestControllerV2(ClinicService clinicService, PetMapper petMapper, AccessPolicy access) {
        this.clinicService = clinicService;
        this.petMapper = petMapper;
        this.access = access;
    }

    @Override
    @PreAuthorize("@access.clinicalUser()")
    public ResponseEntity<PetPageDto> listPetsPage(Integer page, Integer size) {
        int pageNumber = page == null ? 0 : page;
        int pageSize = size == null ? 20 : size;
        if (pageNumber < 0 || pageSize < 1 || pageSize > 100) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid page or size (1-100)");
        }
        var pageable = PageRequest.of(pageNumber, pageSize, Sort.by("id"));
        Page<Pet> pets;
        if (access.clinicalStaff()) {
            pets = clinicService.findPets(pageable);
        } else {
            var visible = access.visiblePets(clinicService.findAllPets()).stream()
                .sorted(java.util.Comparator.comparing(Pet::getId)).toList();
            int start = (int) Math.min(pageable.getOffset(), visible.size());
            pets = new org.springframework.data.domain.PageImpl<>(
                visible.subList(start, Math.min(start + pageSize, visible.size())), pageable, visible.size());
        }
        return new ResponseEntity<>(petMapper.toPetPageDto(pets), HttpStatus.OK);
    }

}
