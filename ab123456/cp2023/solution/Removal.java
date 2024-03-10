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
        if(!system.componentPlacementCon.containsKey(transfer.getComponentId())) // komp nie istnieje
            throw new ComponentDoesNotExist(transfer.getComponentId(), transfer.getSourceDeviceId());
        if(!system.deviceTotalSlotsCon.containsKey(transfer.getSourceDeviceId())) // czy dev zrodlowy istnieje
            throw new DeviceDoesNotExist(transfer.getSourceDeviceId());
        if(!system.componentPlacementCon.get(transfer.getComponentId()).equals(
                transfer.getSourceDeviceId())) // komponent nie znajduje sie na urzadzeniu zrodlowym
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
            system.devices.get(srcId).slotBeingFreed(compId); // budzimy dodawanie, ktore dzedziczy
            // sekcje krytyczna wiec nie zwalniamy muteksa
            usuwanieKomponentu();
            return true;
        } else if (system.transfersTo.containsKey(srcId)) {
            system.devices.get(srcId).slotBeingFreed(compId); // wiemy ze nie obudzimy Dodania
            Relocation doObudzenia = system.transfersTo.get(srcId).remove();
            doObudzenia.obudz(); // dziedziczy sek kryt wiec nie zwalniamy muteksa
            usuwanieKomponentu();
            return true;
        }
        else {
            system.devices.get(srcId).slotBeingFreed(compId); // zwiekszamy wartosc na semaforze
            system.mutex.release();
            usuwanieKomponentu(); // usuwamy
            return true;
        }
    }
    private void usuwanieKomponentu(){
        transfer.prepare();
        system.devices.get(srcId).slotFreed(compId);
        transfer.perform();
        system.duringOperation.remove(compId);
    }
}
