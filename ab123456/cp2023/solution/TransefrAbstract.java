package cp2023.solution;

import cp2023.base.ComponentId;
import cp2023.base.ComponentTransfer;
import cp2023.base.DeviceId;
import cp2023.exceptions.TransferException;

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
     * ta metoda podnosi muteksa i mowi czy transfer jest dozowlony. Jesli ttransfer jest dozwolony to przechodzi do
     * jego wykonania; jesli nie jest dozwolony to oczekuje az bedzei dozwolony i wtedy go wykonuje
     * @return czy transfer byl dozwolony od razu
     */
    public abstract boolean sprobujWykonacTransfer(); // TODO zmienic ta metode na sprobujWykonacTransfer
}
