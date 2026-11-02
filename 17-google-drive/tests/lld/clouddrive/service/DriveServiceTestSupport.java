package lld.clouddrive.service;

import lld.clouddrive.notification.NotificationService;
import lld.clouddrive.permission.InheritedAclPermissionStrategy;
import lld.clouddrive.permission.PermissionService;
import lld.clouddrive.quota.QuotaManager;
import lld.clouddrive.store.BlobStore;
import lld.clouddrive.store.InMemoryBlobStore;
import lld.clouddrive.store.InMemoryNodeRepository;
import lld.clouddrive.store.NodeRepository;
import lld.clouddrive.store.Sha256HashingStrategy;
import lld.clouddrive.util.IdGenerator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Shared test wiring: builds a fully assembled {@link DriveService} with all real (in-memory)
 * collaborators, plus direct handles to {@link BlobStore} and {@link QuotaManager} so tests can
 * assert on internal invariants (ref counts, reserved bytes) that are not otherwise exposed
 * through the facade.
 */
final class DriveServiceTestSupport {

    final NodeRepository nodeRepository = new InMemoryNodeRepository();
    final BlobStore blobStore = new InMemoryBlobStore(new Sha256HashingStrategy());
    final QuotaManager quotaManager;
    final PermissionService permissionService = new PermissionService(new InheritedAclPermissionStrategy());
    final SearchService searchService = new SearchService(permissionService);
    final TrashService trashService;
    final NotificationService notificationService = new NotificationService();
    final DriveService driveService;
    final MutableClock clock = new MutableClock(Instant.parse("2024-01-01T00:00:00Z"));

    DriveServiceTestSupport(long defaultQuotaBytes, Duration retentionPeriod) {
        this.quotaManager = new QuotaManager(defaultQuotaBytes);
        this.trashService = new TrashService(blobStore, quotaManager, nodeRepository, searchService, retentionPeriod);
        DriveNodeFactory factory = new DriveNodeFactory(new IdGenerator("t"), clock);
        this.driveService = new DriveService(nodeRepository, blobStore, quotaManager, permissionService,
                searchService, trashService, notificationService, factory, clock);
    }

    DriveServiceTestSupport() {
        this(1_000_000L, Duration.ofDays(30));
    }

    /** A {@link Clock} whose instant can be advanced on demand, for retention-expiry tests. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
