package in.moonman.hishaab.device.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface DeviceRepository extends JpaRepository<Device, UUID> {

    Optional<Device> findByTokenHashAndRevokedAtIsNull(String tokenHash);

    List<Device> findAllByOrderByCreatedAtAsc();
}
