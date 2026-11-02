package lld.splitwise.repository;

import java.util.List;

import lld.splitwise.model.Settlement;

public interface SettlementRepository {
    Settlement save(Settlement settlement);
    List<Settlement> findByGroupId(String groupId);
}
