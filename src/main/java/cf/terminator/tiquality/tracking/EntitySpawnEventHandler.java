package cf.terminator.tiquality.tracking;

import cf.terminator.tiquality.api.event.TiqualityEvent;
import cf.terminator.tiquality.interfaces.TiqualityEntity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class EntitySpawnEventHandler {

    public static final EntitySpawnEventHandler INSTANCE = new EntitySpawnEventHandler();

    private EntitySpawnEventHandler(){

    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void onSpawn(TiqualityEvent.SetEntityTrackerEvent e){
        TiqualityEntity entity = e.getEntity();
        if(entity instanceof EntityPlayer){
            e.setTracker(ForcedTracker.INSTANCE);
        }
    }
}
