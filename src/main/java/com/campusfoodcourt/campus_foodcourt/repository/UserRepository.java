package com.campusfoodcourt.campus_foodcourt.repository;



import com.campusfoodcourt.campus_foodcourt.entity.AccountStatus;
import com.campusfoodcourt.campus_foodcourt.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;


/**
 * Lock order: every path that changes an <i>existing</i> user's refresh tokens locks that user's row first, then
 * token rows. (Registration is the exception and needs no lock: the new user row is invisible to other
 * transactions until it commits.)
 * <ul>
 *   <li>Session operations (login, refresh, logout) take {@code FOR SHARE} ({@link #findByIdForShare}); they do not
 *       block each other.</li>
 *   <li>A status change takes the row lock of its {@code UPDATE users} statement ({@code FOR NO KEY UPDATE} level),
 *       which conflicts with {@code FOR SHARE}: {@link #updateAccountStatus} always, and
 *       {@link #updateAccountStatusIfDifferent} (used for deactivation) only when the status actually changes; when
 *       it matches no row it takes no lock and has no side effects. A status change and a session operation of the
 *       same user are therefore serialised, and, with this single order, cannot deadlock.</li>
 * </ul>
 */
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);

    /** SELECT ... FOR SHARE: waits for (and holds off) a concurrent status change, not other sessions. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForShare(@Param("id") Long id);

    /** Current status read from the database (a scalar query never returns persistence-context state). */
    @Query("select u.accountStatus from User u where u.id = :id")
    Optional<AccountStatus> findAccountStatusById(@Param("id") Long id);

    /**
     * Sets the status directly in the database, row-locking the user. Pending changes are flushed first and the
     * persistence context is cleared afterwards, so no stale User state survives in the calling transaction.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update User u set u.accountStatus = :status where u.id = :id")
    int updateAccountStatus(@Param("id") Long id, @Param("status") AccountStatus status);

    /**
     * Like {@link #updateAccountStatus} but only if the status actually changes; returns 0 when the user is unknown
     * or already has that status. Under READ COMMITTED a concurrent identical change waits for the row lock and then
     * re-evaluates the condition against the committed row, so exactly one of them reports the transition.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update User u set u.accountStatus = :status where u.id = :id and u.accountStatus <> :status")
    int updateAccountStatusIfDifferent(@Param("id") Long id, @Param("status") AccountStatus status);

    /** Current access-token version read from the database (see V9 migration). */
    @Query("select u.tokenVersion from User u where u.id = :id")
    Optional<Long> findTokenVersionById(@Param("id") Long id);

    /** Invalidates every access token issued so far for the user. Same flush/clear behaviour as above. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update User u set u.tokenVersion = u.tokenVersion + 1 where u.id = :id")
    int incrementTokenVersion(@Param("id") Long id);
}
