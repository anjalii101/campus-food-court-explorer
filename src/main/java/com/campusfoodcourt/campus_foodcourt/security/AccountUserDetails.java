package com.campusfoodcourt.campus_foodcourt.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.io.Serial;
import java.util.Collection;

/** Spring Security user that also carries the account's current access-token version (users.token_version). */
public final class AccountUserDetails extends User {

    @Serial
    private static final long serialVersionUID = 1L;

    private final long tokenVersion;

    public AccountUserDetails(String email, String password, boolean enabled, boolean accountNonLocked,
                              Collection<? extends GrantedAuthority> authorities, long tokenVersion) {
        super(email, password, enabled, true, true, accountNonLocked, authorities);
        this.tokenVersion = tokenVersion;
    }

    public long tokenVersion() {
        return tokenVersion;
    }
}
