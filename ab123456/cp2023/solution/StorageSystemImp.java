package cp2023.solution;

import cp2023.base.ComponentId;
import cp2023.base.ComponentTransfer;
import cp2023.base.DeviceId;
import cp2023.base.StorageSystem;
import cp2023.exceptions.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;

public class StorageSystemImp implements StorageSystem {
    // purposely public in package
    final ConcurrentHashMap<DeviceId, Integer> deviceTotalSlotsCon;
    final ConcurrentHashMap<DeviceId, Device> devices;
    final ConcurrentHashMap<ComponentId, DeviceId> componentPlacementCon;
    final ConcurrentHashMap<ComponentId, Boolean> duringOperation;
    final Semaphore mutex;

    final ConcurrentHashMap<DeviceId, Integer> additionsWaiting;

    final ConcurrentHashMap<DeviceId, ConcurrentLinkedQueue<Relocation>> transfersTo;
    // transfers ending a cycle

    public StorageSystemImp(Map<DeviceId, Integer> deviceTotalSlots, Map<ComponentId, DeviceId> componentPlacement){
        deviceTotalSlotsCon = new ConcurrentHashMap<>();
        componentPlacementCon = new ConcurrentHashMap<>();
        duringOperation = new ConcurrentHashMap<>();
        additionsWaiting = new ConcurrentHashMap<>();
        transfersTo = new ConcurrentHashMap<>();
        ConcurrentHashMap<DeviceId, Integer> reservedSlots = new ConcurrentHashMap<>();
        // temporary map to check if the declared device capacity is not exceeded
        devices = new ConcurrentHashMap<>();
        mutex = new Semaphore(1);
        for(DeviceId dev : deviceTotalSlots.keySet()) {  // iterates over devices
            reservedSlots.put(dev, 0);
            devices.put(dev, new Device(deviceTotalSlots.get(dev), componentPlacement, dev));
        }

        deviceTotalSlotsCon.putAll(deviceTotalSlots);
        componentPlacementCon.putAll(componentPlacement);
        for(ComponentId component : componentPlacement.keySet()) { // iterates over components
            duringOperation.put(component, false);
            // if the device in componentPlacement is not on the list of devices, something is wrong
            if(!deviceTotalSlots.containsKey(componentPlacement.get(component)))
                throw new IllegalArgumentException("in componentPlacement is a component which is not in deviceTotalSlots");
            reservedSlots.put(componentPlacement.get(component),
                    reservedSlots.get(componentPlacement.get(component)) + 1); // incrementing the number of components on the device
        }
        for(Map.Entry<DeviceId, Integer> pair : reservedSlots.entrySet()){
            if (pair.getValue() > deviceTotalSlots.get(pair.getKey()))
                throw new IllegalArgumentException("Number " + pair.getValue() + " of components on the device: " +
                        pair.getKey() + " exceeds capacity of this device: " +
                        deviceTotalSlots.get(pair.getKey()) );
        }

    }

    /**
     * defines the type of transfer and checks its correctness. In case of incorrect transfer, it throws an
     * appropriate exception. The return value takes values 1 - 3
     * 1 - adding a component
     * 2 - moving the component
     * 3 - removing the component
     * 0 - transfer is incorrect
     * */
    private int transferType(ComponentTransfer transfer) throws TransferException{
        // defininig the type and correctness of the transfer
        int ttype = 0;
        if(transfer.getSourceDeviceId() == null && transfer.getDestinationDeviceId() != null){  // 1 - addition
            Addition.isAdditionCorrect(transfer, this);
            ttype = 1;
        }
        if(transfer.getSourceDeviceId() != null && transfer.getDestinationDeviceId() != null){ // 2 - moving
            Relocation.isRelocationCorrect(transfer, this);
            ttype = 2;
        }
        if(transfer.getSourceDeviceId() != null && transfer.getDestinationDeviceId() == null) { // 3 - deletion
            Removal.isRemovalCorrect(transfer, this);
            ttype = 3;
        }

        if(ttype == 0)
            throw new IllegalTransferType(transfer.getComponentId());


        return ttype;
    }

    @Override
    public void execute(ComponentTransfer transfer) throws TransferException {
        int ttype;
        try {
            mutex.acquire();
            assert (mutex.availablePermits() == 0);
            ttype = transferType(transfer);
            // can throw an exception, so we release the mutex in finally
            TransefrAbstract transfer1;
            if(ttype == 1) {// component addition
                transfer1 = new Addition(this, transfer);
                transfer1.tryPerformTransfer();
            }
            else if (ttype == 2){  // component relocation
                transfer1 = new Relocation(this, transfer);
                transfer1.tryPerformTransfer();
            }
            else if (ttype == 3) { // component removal
                transfer1 = new Removal(this, transfer);
                transfer1.tryPerformTransfer();
            }

        }
        catch (InterruptedException e){
            throw new RuntimeException("panic: unexpected thread interruption");
        }
        catch (TransferException e){
            // in the case of an incorrect transfer, we release the mutex and throw an exception further
            mutex.release();
            throw e;
        }
    }
}
