package com.campusfoodcourt.campus_foodcourt.entity;
import jakarta.persistence.*;
import lombok.*;


@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;


    private String name;


    @Column(unique = true, nullable = false)
    private String email;


    @Column(nullable = false)
    private String password;


    private String role; // ROLE_USER, ROLE_ADMIN


    /** Only ACTIVE users may log in, refresh or use an access token. Never set from client input. */
    @Enumerated(EnumType.STRING)
    @Column(name = "account_status", nullable = false, length = 20)
    @Builder.Default
    private AccountStatus accountStatus = AccountStatus.ACTIVE;


    /** Version embedded in access tokens; incremented on deactivation to invalidate every earlier access token. */
    @Column(name = "token_version", nullable = false)
    @Builder.Default
    private long tokenVersion = 0;


    public boolean isActive() {
        return accountStatus == AccountStatus.ACTIVE;
    }
}