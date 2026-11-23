package nexo;

import jakarta.persistence.*;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

@Entity
@Table(name = "account")
class Account {
    @Id UUID id;
    @Column(nullable = false, length = 254) String email;
    @Column(name = "password_hash", nullable = false, length = 100) String passwordHash;
    @Column(nullable = false, length = 20) String role;
    @Column(nullable = false) boolean enabled;

    protected Account() {}

    Account(String email, String passwordHash, String role) {
        this.id = UUID.randomUUID();
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.enabled = true;
    }
}

interface Accounts extends JpaRepository<Account, UUID> {
    Optional<Account> findByEmail(String email);
}
