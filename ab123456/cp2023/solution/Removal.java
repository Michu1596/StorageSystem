package cp2023.solution;

import cp2023.base.ComponentTransfer;
import cp2023.exceptions.ComponentDoesNotExist;
import cp2023.exceptions.ComponentIsBeingOperatedOn;
import cp2023.exceptions.DeviceDoesNotExist;
import cp2023.exceptions.TransferException;


public class Removal extends TransefrAbstract{
    public Removal(StorageSystemImp system, ComponentTransfer transfer){
        super(system, transfer);
    }

    public static boolean isRemovalCorrect(ComponentTransfer transfer,
                                           StorageSystemImp system) throws TransferException{
        if(!system.componentPlacementCon.containsKey(transfer.getComponentId())) // component does not exist
            throw new ComponentDoesNotExist(transfer.getComponentId(), transfer.getSourceDeviceId());
        if(!system.deviceTotalSlotsCon.containsKey(transfer.getSourceDeviceId())) // if source device does not exist
            throw new DeviceDoesNotExist(transfer.getSourceDeviceId());
        if(!system.componentPlacementCon.get(transfer.getComponentId()).equals(
                transfer.getSourceDeviceId())) // component is not on the source device
            throw new ComponentDoesNotExist(transfer.getComponentId(), transfer.getSourceDeviceId());
        if(system.duringOperation.get(transfer.getComponentId()))
            throw new ComponentIsBeingOperatedOn(transfer.getComponentId());
        return true;
    }
    @Override
    public boolean tryPerformTransfer(){
        system.duringOperation.put(compId, true);
        system.componentPlacementCon.remove(compId);
        if (system.additionsWaiting.containsKey(srcId)){
            system.devices.get(srcId).slotBeingFreed(compId); // we awake the addition which inherits the critical section
            componentRemoval();
            return true;
        } else if (system.transfersTo.containsKey(srcId)) {
            system.devices.get(srcId).slotBeingFreed(compId); // addition will not be awakened
            Relocation toBeAwakened = system.transfersTo.get(srcId).remove();
            toBeAwakened.wakeUp();
            componentRemoval();
            return true;
        }
        else {
            system.devices.get(srcId).slotBeingFreed(compId); // semaphore is increased
            system.mutex.release();
            componentRemoval();
            return true;
        }
    }
    private void componentRemoval(){
        transfer.prepare();
        system.devices.get(srcId).slotFreed(compId);
        transfer.perform();
        system.duringOperation.remove(compId);
    }
}
