package org.alfresco.repo.domain.node.ibatis.cqrs;

import org.alfresco.repo.domain.node.NodeEntity;
import org.alfresco.repo.domain.node.ibatis.cqrs.utils.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Reader which uses our implementation for retrieve the node from his own.
 * Uses org.alfresco.repo.domain.node.AbstractNodeDAOImpl#getNodePair(java.lang.Long) for retrieve the id.
 *
 * NOTICE Thise reader isn't used in this example
 *
 * Created by mmuller on 26/03/2018.
 */
public class IbatisNodeInsertCqrsReader3 extends IbatisNodeInsertCqrsReaderAbstract {
    private IbatisNodeInsertCqrsServiceImpl ibatisCqrsService;

    public IbatisNodeInsertCqrsReader3(String name, IbatisNodeInsertCqrsServiceImpl ibatisCqrsService) {
        super(name);
        this.ibatisCqrsService = ibatisCqrsService;
    }

    @Override
    public String getValue(String col, Object node)
    {
        if(col.equalsIgnoreCase("id"))
        {
            Long searchId = ((NodeEntity) node).getId();
            return ibatisCqrsService.getNodeDAOImpl().getNodePair(searchId).getFirst().toString();
        }
        return null;
    }

    @Override
    public void onUpdate(List<Event> events)
    {
        // not implemented yet
    }

    @Override
    public void onCreate(List<Event> events)
    {
        Logger.logDebug(this.getName() + " detected " + events.size() + " new events:", ibatisCqrsService.getContext());
        events.forEach(e -> {
            Object passStatementObject = e.getDiffObject();
            Logger.logDebug("  ---------------------------------", ibatisCqrsService.getContext());
            Logger.logDebug("  " + e.toString(), ibatisCqrsService.getContext());
            Logger.logDebug("  ---------------------------------", ibatisCqrsService.getContext());
            ibatisCqrsService.getNodeDAOImpl().insertNode((NodeEntity) passStatementObject);
        });
    }

    @Override
    public void onDelete(List<Event> events)
    {
        // not implemented yet
    }

    @Override
    public List<Object> getUsedStores()
    {
        ArrayList<Object> stores = new ArrayList<>();
        stores.add(ibatisCqrsService.getNodeDAOImpl());
        return stores;
    }
}
