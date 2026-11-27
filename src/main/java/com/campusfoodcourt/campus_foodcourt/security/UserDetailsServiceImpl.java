package com.campusfoodcourt.campus_foodcourt.security;

import com.campusfoodcourt.campus_foodcourt.entity.AccountStatus;
import com.campusfoodcourt.campus_foodcourt.entity.User;
import com.campusfoodcourt.campus_foodcourt.repository.UserRepository;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Loads users by email (the JWT subject) with their single role as granted authority. The account status is
 * mapped onto the standard flags (SUSPENDED: locked, DEACTIVATED or anything else not ACTIVE: disabled), and the
 * current access-token version is included; {@link JwtAuthenticationFilter} checks both on every request.
 */
@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    public UserDetailsServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
        return new AccountUserDetails(user.getEmail(), user.getPassword(),
                user.isActive() || user.getAccountStatus() == AccountStatus.SUSPENDED,   // enabled
                user.getAccountStatus() != AccountStatus.SUSPENDED,                      // not locked
                AuthorityUtils.createAuthorityList(user.getRole()),
                user.getTokenVersion());
    }
}
