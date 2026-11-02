package lld.splitwise.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import lld.splitwise.model.Settlement;

public final class InMemorySettlementRepository implements SettlementRepository {

    private final Map<String, List<Settlement>> settlementsByGroup = new ConcurrentHashMap<>();

    @Override
    public Settlement save(Settlement settlement) {
        String key = settlement.groupId() == null ? "__DIRECT__" : settlement.groupId();
        settlementsByGroup.computeIfAbsent(key, g -> new CopyOnWriteArrayList<>()).add(settlement);
        return settlement;
    }

    @Override
    public List<Settlement> findByGroupId(String groupId) {
        String key = groupId == null ? "__DIRECT__" : groupId;
        return new ArrayList<>(settlementsByGroup.getOrDefault(key, List.of()));
    }
}
