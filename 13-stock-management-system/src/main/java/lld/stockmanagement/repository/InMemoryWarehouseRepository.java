package lld.stockmanagement.repository;

import lld.stockmanagement.model.Warehouse;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory {@link WarehouseRepository}; same rationale as {@link InMemorySkuRepository}. */
public final class InMemoryWarehouseRepository implements WarehouseRepository {

    private final ConcurrentHashMap<String, Warehouse> warehouses = new ConcurrentHashMap<>();

    @Override
    public void register(Warehouse warehouse) {
        warehouses.putIfAbsent(warehouse.getWarehouseCode(), warehouse);
    }

    @Override
    public Optional<Warehouse> findByCode(String warehouseCode) {
        return Optional.ofNullable(warehouses.get(warehouseCode));
    }

    @Override
    public boolean exists(String warehouseCode) {
        return warehouses.containsKey(warehouseCode);
    }
}
