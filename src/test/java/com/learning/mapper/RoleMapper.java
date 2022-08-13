package com.learning.mapper;

import com.learning.po.Role;

/**
 * @author chenzt
 * @date 2022/8/12
 */
public interface RoleMapper {
    public Role getRole(Long id);

    public Role findRole(String roleName);

    public int deleteRole(Long id);

    public int insertRole(Role role);
}
