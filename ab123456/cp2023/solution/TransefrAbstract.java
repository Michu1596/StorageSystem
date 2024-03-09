package cp2023.solution;

import cp2023.base.ComponentId;
import cp2023.base.ComponentTransfer;
import cp2023.base.DeviceId;

public abstract class TransefrAbstract {
    final StorageSystemImp system;
    final ComponentTransfer transfer;
    final DeviceId srcId;
    final DeviceId destId;
    final ComponentId compId;

    public TransefrAbstract(StorageSystemImp system, ComponentTransfer transfer){
        this.system = system;
        this.transfer = transfer;
        srcId = transfer.getSourceDeviceId();
        destId = transfer.getDestinationDeviceId();
        compId = transfer.getComponentId();
    }

    /**
     * this method acuires the mutex and checks if the transfer is allowed. If the transfer is allowed, it performs it;
     * if it is not allowed, it waits until it is allowed and then performs it
     * @return if transfer was performed instantly
     */
    public abstract boolean tryPerformTransfer();
}
