package com.equipmentmanagement.auth;

public record AppUser(Long id, String username, String name, String role, String status) {
    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }
}
