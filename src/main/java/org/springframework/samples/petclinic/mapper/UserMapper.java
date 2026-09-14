package org.springframework.samples.petclinic.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.springframework.samples.petclinic.model.Role;
import org.springframework.samples.petclinic.model.User;
import org.springframework.samples.petclinic.rest.dto.RoleDto;
import org.springframework.samples.petclinic.rest.dto.UserDto;

import java.util.Collection;

/**
 * Map User/Role & UserDto/RoleDto using mapstruct
 */
@Mapper
public interface UserMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "user", ignore = true)
    Role toRole(RoleDto roleDto);

    RoleDto toRoleDto(Role role);

    Collection<RoleDto> toRoleDtos(Collection<Role> roles);

    User toUser(UserDto userDto);

    @Mapping(target = "authCode", expression = "java(toAuthCode(user.getRoles()))")
    UserDto toUserDto(User user);

    default String toAuthCode(Collection<Role> roles) {
        if (roles == null) {
            return null;
        }
        String result = null;
        for (Role role : roles) {
            if (role == null || role.getName() == null) {
                continue;
            }
            String name = role.getName();
            if (name.startsWith("ROLE_")) {
                name = name.substring(5);
            }
            switch (name) {
                case "ADMIN":
                    return "ADMIN";
                case "VET_ADMIN":
                case "VET":
                case "VETS":
                    result = "VETS";
                    break;
                case "OWNER_ADMIN":
                case "OWNER":
                    if (result == null) {
                        result = "OWNER";
                    }
                    break;
                default:
                    break;
            }
        }
        return result;
    }

    Collection<Role> toRoles(Collection<RoleDto> roleDtos);

}
