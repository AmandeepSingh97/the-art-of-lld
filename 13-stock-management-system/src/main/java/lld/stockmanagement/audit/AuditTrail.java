package lld.stockmanagement.audit;

import lld.stockmanagement.model.InventoryKey;
import lld.stockmanagement.model.StockMovement;

import java.util.List;

/**
 * Append-only store of {@link StockMovement} records. Deliberately exposes no
 * delete/update method — an audit trail that can be edited is not an audit trail.
 */
public interface AuditTrail {

    void record(StockMovement movement);

    List<StockMovement> history(InventoryKey key);

    List<StockMovement> all();
}
