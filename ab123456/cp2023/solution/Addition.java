package cp2023.solution;

import cp2023.base.ComponentTransfer;
import cp2023.exceptions.ComponentAlreadyExists;
import cp2023.exceptions.DeviceDoesNotExist;
import cp2023.exceptions.TransferException;
import java.util.concurrent.Semaphore;

public class Addition extends TransefrAbstract {

    private Semaphore realSlotSem;

    /**
     * check if the transfer is correct, provided that it is an addition transfer
     * @param transfer transfer to be performed
     * @param system system which will perform the transfer
     * @return true if the transfer is correct
     * @throws TransferException if transfer is incorrect
     */
    public static boolean isAdditionCorrect(ComponentTransfer transfer,
                                            StorageSystemImp system) throws TransferException {
        if(!system.deviceTotalSlotsCon.containsKey(transfer.getDestinationDeviceId())) // if device does not exist
            throw new DeviceDoesNotExist(transfer.getDestinationDeviceId());
        if(system.componentPlacementCon.containsKey(transfer.getComponentId())) // component already exists
            throw new ComponentAlreadyExists(transfer.getComponentId(),
                    system.componentPlacementCon.get(transfer.getComponentId()));
        return true;
    }

    public Addition(StorageSystemImp system, ComponentTransfer transfer){
        super(system, transfer);
    }

    private void componentAddition(boolean permission){
        try {
            if(!permission) {
                realSlotSem = system.devices.get(destId).czekajNaMiejsce(compId);
                // we will wait for the semaphore to be released by another transfer. However, BEFORE it is released, the
                // mutex will be released, so that the information about the system can be updated safely

                system.additionsWaiting.put(destId, system.additionsWaiting.get(destId) - 1);
                // after we get realSlotSem, we decrease the number of waiting transfers

                if (system.additionsWaiting.get(destId) == 0)
                    system.additionsWaiting.remove(destId);

                system.componentPlacementCon.put(compId, destId);
                // information about the component placement is updated, so transfer tryin to place the component on the
                // same device will be incorrect
            }
            transfer.prepare(); // first stage of the transfer is performed
            realSlotSem.acquire(); // waiting for the predecessor to release the semaphore
            transfer.perform(); // final stage of the transfer is performed
            system.duringOperation.put(compId, false);
        }
        catch (InterruptedException e){
            throw new RuntimeException("panic: unexpected thread interruption");
        }

    }

    /**
     * if the transfer is allowed, it updates system information, in case it is not, system info is updated
     * in componentAddition method after the slot is granted. This method works in mutex!
     * @return if transfer was successful
     */
    @Override
    public boolean tryPerformTransfer(){
        system.duringOperation.put(compId, true);
        if(system.devices.get(destId).czyWolne()){
            system.componentPlacementCon.put(compId, destId);
            realSlotSem = system.devices.get(destId).czekajNaMiejsce(compId);
            system.mutex.release();
            componentAddition(true);
            return true;
        }
        if(system.additionsWaiting.containsKey(destId))
            system.additionsWaiting.put(destId, system.additionsWaiting.get(destId) + 1);
        else
            system.additionsWaiting.put(destId, 1);

        system.mutex.release(); // realease the mutex, because thread will wait for the semaphore
        // in componentAddition method
        componentAddition(false);
        return false;
    }
}
