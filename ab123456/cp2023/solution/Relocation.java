package cp2023.solution;

import cp2023.base.ComponentTransfer;
import cp2023.base.DeviceId;
import cp2023.exceptions.*;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.*;

public class Relocation extends TransefrAbstract{

    private final CountDownLatch latch; // this is used to wake up the transfer
    private CyclicBarrier barrier; // synchronizes the transfers in a cycle
    private boolean mutexRelease; // if set then transfer should inherit the critical section and release the mutex
    private boolean inCycle;
    private boolean inPath;
    private boolean lastInPath;
    private Semaphore realSlot;
    private Semaphore lastInPathSlot;
    // first element of the path gives the last element the real slot it is waiting for, the last element puts it on
    // destDevice and waits for it

    public static boolean isRelocationCorrect(ComponentTransfer transfer,
                                              StorageSystemImp system) throws TransferException{
        if(!system.componentPlacementCon.containsKey(transfer.getComponentId()))
            throw new ComponentDoesNotExist(transfer.getComponentId(), transfer.getSourceDeviceId());
        if(!system.deviceTotalSlotsCon.containsKey(transfer.getDestinationDeviceId())) // if dest device does not exist
            throw new DeviceDoesNotExist(transfer.getDestinationDeviceId());
        if(!system.deviceTotalSlotsCon.containsKey(transfer.getSourceDeviceId())) // if source device does not exist
            throw new DeviceDoesNotExist(transfer.getSourceDeviceId());
        if(system.duringOperation.get(transfer.getComponentId()))
            throw new ComponentIsBeingOperatedOn(transfer.getComponentId());
        if(!system.componentPlacementCon.get(transfer.getComponentId()).equals(
                transfer.getSourceDeviceId())) // component is not on the source device
            throw new ComponentDoesNotExist(transfer.getComponentId(), transfer.getSourceDeviceId());
        if(system.componentPlacementCon.get(transfer.getComponentId()).equals(
                transfer.getDestinationDeviceId())) // component is already on the destination device
            throw new ComponentDoesNotNeedTransfer(transfer.getComponentId(), transfer.getSourceDeviceId());

        return true;
    }
    public Relocation(StorageSystemImp system, ComponentTransfer transfer){
        super(system, transfer);
        latch = new CountDownLatch(1);
        mutexRelease = false;
    }
    private boolean ifCycle(DeviceId source, int pathLength){
        if(system.transfersTo.containsKey(source)){
            ConcurrentLinkedQueue<Relocation> list = system.transfersTo.get(source);
            for(Iterator<Relocation> itr = list.iterator(); itr.hasNext(); ){
                Relocation relocation = itr.next();
                // checking if the source is the same as the destination
                // if it is, we have found a cycle
                if(relocation.srcId.equals(destId) ) {
                    barrier = new CyclicBarrier(pathLength + 1);
                    // if the source is the same as the destination, we have found a cycle
                    // we create a cyclic barrier that is shared by all the threads in the cycle
                    relocation.barrier = barrier;
                    relocation.inCycle = true;

                    relocation.latch.countDown(); // transfers are woken up one by one
                    itr.remove();
                    if(list.isEmpty())
                        system.transfersTo.remove(source);
                    return true;

                } else if (ifCycle(relocation.srcId, pathLength + 1)) {
                    // if there is a cycle, we can wake up the transfers that belong to it
                    relocation.barrier = barrier;
                    relocation.inCycle = true;

                    relocation.latch.countDown(); // transfers are woken up one by one
                    itr.remove();
                    if(list.isEmpty())
                        system.transfersTo.remove(source);
                    return true;
                }
            }
        }
        return false;
    }

    // if permission is true, the transfer is allowed to perform instantly
    private void perform(boolean permission){
        try {

            // waiting
            if(!permission) {
                latch.await();
                // after the transfer is woken up, it is in the mutex, but it does not release it immediately, because
                // many threads are woken up at once; the one that woke them up releases the mutex
                system.duringOperation.put(compId, true);
                system.componentPlacementCon.put(compId, destId); // srcId is replaced by destId in the map
                if(inPath && lastInPath) { // last on the path frees the slot
                    system.devices.get(srcId).slotBeingFreed(compId);
                }
                barrier.await();
                if(mutexRelease){
                    // if it is not in a cycle and it was woken up in the critical section, it has to release the mutex
                    system.mutex.release();
                }
            }

            if(inCycle) {
                Semaphore semForTransferredComp = system.devices.get(srcId).getLoweredSem(compId);
                // we need to wait for the component transfer to be completed
                transfer.prepare();
                barrier.await();
                // when all the transfers have completed the prepare stage, we can move on
                system.devices.get(destId).addLoweredSem(semForTransferredComp, compId);
            } else if (inPath) {
                if(lastInPath) {
                    lastInPathSlot.acquire();
                    barrier.await();
                    transfer.prepare();
                    system.devices.get(destId).addLoweredSem(lastInPathSlot, compId);
                    // penultimate element in the path gets the semaphore that was given to the first element in the path

                    barrier.await();
                    system.devices.get(srcId).slotFreed(compId);
                }
                else {
                    barrier.await(); // other cases are waiting for the last element in the path
                    Semaphore relocatedCompSem = system.devices.get(srcId).getLoweredSem(compId);
                    transfer.prepare();
                    barrier.await(); // when all the transfers have completed the prepare stage, we can move on
                    system.devices.get(destId).addLoweredSem(relocatedCompSem, compId);
                }
            }
            else {
                transfer.prepare();
                system.devices.get(srcId).slotFreed(compId); // after prepare, we "free" the slot on srcDev

                realSlot.acquire();
                // before the transfer is performed, it has to make sure that there is a slot on the device
            }

            transfer.perform();

            system.duringOperation.put(compId, false);
            // this needn't be in the mutex; transfers on this component can start even when the rest of the transfers
            // in the cycle are being performed

            system.mutex.acquire();
            if (system.transfersTo.containsKey(destId)) {
                system.transfersTo.get(destId).remove(this); // we remove the transfer from the list of transfers
                if(system.transfersTo.get(destId).isEmpty())
                    system.transfersTo.remove(destId);
            }
            system.mutex.release();
        }
        catch (InterruptedException | BrokenBarrierException e) {
            throw new RuntimeException("panic: unexpected thread interruption");
        }

    }

    /**
     * method to wake up a single transfer that is not part of a cycle
     */
    public void obudz(){
        barrier = new CyclicBarrier(1);
        // does not have to wait for other threads, because it is the only one
        mutexRelease = true; // inherited the critical section
        inCycle = false;
        realSlot = system.devices.get(destId).waitForSlot(compId);
        // we know that we will not have to wait, because we were woken up by the removal of the component

        system.duringOperation.put(compId, true);
        system.componentPlacementCon.put(compId, destId); // srcId is replaced by destId in the map

        if(system.additionsWaiting.containsKey(srcId)) { // in the first place we check if there is an addition waiting
            system.devices.get(srcId).slotBeingFreed(compId);
            mutexRelease = false;
        }
        else if (system.transfersTo.containsKey(srcId)) { // path
            DeviceId id = srcId;
            inPath = true;
            ArrayList<Relocation> path = new ArrayList<>();
            Relocation temp;
            while (system.transfersTo.containsKey(id)) {
                temp = system.transfersTo.get(id).remove();
                path.add(temp);

                if (system.transfersTo.get(id).isEmpty())
                    system.transfersTo.remove(id);

                id = temp.destId;
            }
            barrier = new CyclicBarrier(path.size() + 1);
            path.get(path.size()-1).lastInPath = true;
            path.get(path.size()-1).lastInPathSlot = realSlot;
            system.devices.get(destId).getLoweredSem(compId);
            for(Relocation relocation : path){
                relocation.inPath = true;
                relocation.barrier = barrier;
                relocation.inCycle = false;
                relocation.latch.countDown();
            }
        }
        else {
            system.devices.get(srcId).slotBeingFreed(compId);
        }

        latch.countDown();
    }

    @Override
    public boolean tryPerformTransfer() {
        system.duringOperation.put(compId, true);
        // till the transfer is performed, the component cannot be subject to other transfers

        if (ifCycle(srcId, 1) ) { // cycle is priority
            inCycle = true;
            system.componentPlacementCon.put(compId, destId); // srcId is replaced by destId in the map
            try {
                barrier.await();
                // we wait for all the threads to modify the metadata and release the mutex
            } catch (BrokenBarrierException | InterruptedException e) {
                throw new RuntimeException("panic: unexpected thread interruption");
            }
            system.mutex.release();
            perform(true);
            return true;
        } else if (system.devices.get(destId).isFree()) {
            inCycle = false;
            realSlot = system.devices.get(destId).waitForSlot(compId);
            system.componentPlacementCon.put(compId, destId); // srcId is replaced by destId in the map
            if(system.additionsWaiting.containsKey(srcId)) { // if addition transfer waits, it inherits the critical section
                system.devices.get(srcId).slotBeingFreed(compId);
                // we inform that there is a free slot on the source device
            }
            else if(system.transfersTo.containsKey(srcId)){ // awaken transfer inherits the critical section
                DeviceId id = srcId;
                inPath = true;
                ArrayList<Relocation> path = new ArrayList<>();
                Relocation temp;
                while (system.transfersTo.containsKey(id)) {
                    temp = system.transfersTo.get(id).remove();
                    path.add(temp);

                    if (system.transfersTo.get(id).isEmpty())
                        system.transfersTo.remove(id);

                    id = temp.destId;
                }
                barrier = new CyclicBarrier(path.size() + 1);
                path.get(path.size()-1).lastInPath = true;
                path.get(path.size()-1).lastInPathSlot = realSlot;
                system.devices.get(destId).getLoweredSem(compId);
                for(Relocation relocation : path){
                    relocation.inPath = true;
                    relocation.barrier = barrier;
                    relocation.inCycle = false;
                    relocation.latch.countDown();
                }
            }
            else{
                system.devices.get(srcId).slotBeingFreed(compId); // we inform that there is a free slot on the source device
                system.mutex.release();
            }
            barrier = new CyclicBarrier(1); // doesn't have to wait for other threads, because it is the only one
            perform(true);
            return true;

        } else { // cycle does not exist and there is no free slot
            if (system.transfersTo.containsKey(destId))
                system.transfersTo.get(destId).add(this);
            else {
                ConcurrentLinkedQueue<Relocation> newQueue = new ConcurrentLinkedQueue<>();
                newQueue.add(this);
                system.transfersTo.put(destId, newQueue);
            }
            system.mutex.release();
            perform(false); // we have to wait for the slot
            return false;
        }
    }

    @Override
    public String toString(){
        return "FROM " + srcId + " TO " + destId + " " + compId;
    }
}
