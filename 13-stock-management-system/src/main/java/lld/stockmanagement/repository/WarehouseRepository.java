package lld.stockmanagement.repository;

import lld.stockmanagement.model.Warehouse;
import java.util.Optional;

/** Repository abstraction for warehouses, mirroring {@link SkuRepository}. */
public interface WarehouseRepository {

    void register(Warehouse warehouse);

    Optional<Warehouse> findByCode(String warehouseCode);

    boolean exists(String warehouseCode);
}
